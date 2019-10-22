package devbox
import java.io.{InterruptedIOException, PrintWriter, StringWriter}
import java.nio.ByteBuffer
import java.nio.file.{Files, LinkOption}
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent._
import java.util.concurrent.atomic.AtomicInteger

import devbox.common._
import os.Path
import upickle.default
import Util.relpathRw
import cask.util
import devbox.Syncer.{ExitCode, NoOp, SyncFail, synchronizeRepo, updateSkipPredicate}
import sourcecode.{File, Line, Text}

import scala.annotation.tailrec
import scala.collection.{immutable, mutable}
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal



object AgentReadWriteActor{
  sealed trait Msg
  case class Send(value: Rpc) extends Msg
  case class Restarted() extends Msg
  case class ReadRestarted() extends Msg
  case class Receive(data: Array[Byte]) extends Msg
}
class AgentReadWriteActor(agent: AgentApi,
                          syncer: => SyncActor)
                         (implicit ac: ActorContext)
  extends SimpleActor[AgentReadWriteActor.Msg](){
  private val buffer = mutable.ArrayDeque.empty[Rpc]

  var sending = true
  def run(item: AgentReadWriteActor.Msg): Unit = item match{
    case AgentReadWriteActor.Send(msg) =>
      ac.onSchedule()
      buffer.append(msg)
      if (sending) sendMessages(Seq(msg))

    case AgentReadWriteActor.ReadRestarted() =>
      if (sending){
        restart()
      }
    case AgentReadWriteActor.Restarted() =>
      if (!sending){

        sending = true
        Syncer.spawnReaderThread(
          agent,
          buf => this.send(AgentReadWriteActor.Receive(buf)),
          () => this.send(AgentReadWriteActor.ReadRestarted())
        )
        sendMessages(buffer.toSeq)
      }


    case AgentReadWriteActor.Receive(data) =>
      syncer.send(SyncActor.Receive(upickle.default.readBinary[Response](data)))
      ac.onComplete()
      buffer.dropInPlace(1)
  }

  def sendMessages(msgs: Seq[Rpc]) = {
    try {
      for(msg <- msgs){
        val blob = upickle.default.writeBinary(msg)
        agent.stdin.writeBoolean(true)
        agent.stdin.writeInt(blob.length)
        agent.stdin.write(blob)
        agent.stdin.flush()
      }
    }catch{ case e: java.io.IOException =>
      restart()
    }
  }

  def restart() = {
    agent.destroy()
    agent.start()
    sending = false
    this.send(AgentReadWriteActor.Restarted())
  }
}

object SyncActor{
  sealed trait Msg
  case class Scan() extends Msg

  case class Events(paths: Set[os.Path]) extends Msg
  case class Debounced(debounceId: Object) extends Msg
  case class Receive(value: devbox.common.Response) extends Msg
  case class Retry() extends Msg
}
class SyncActor(skipArr: Array[(os.Path, Boolean) => Boolean],
                agentReadWriter: => AgentReadWriteActor,
                mapping: Seq[(os.Path, os.RelPath)],
                logger: Logger,
                signatureTransformer: (os.RelPath, Signature) => Signature,
                skipper: Skipper,
                scheduledExecutorService: ScheduledExecutorService)
               (implicit ac: ActorContext)
  extends StateMachineActor[SyncActor.Msg]() {

  def initialState = Initializing(Set())

  case class Initializing(changedPaths: Set[os.Path]) extends State({
    case SyncActor.Events(paths) => Initializing(changedPaths ++ paths)
    case SyncActor.Scan() =>
      agentReadWriter.send(AgentReadWriteActor.Send(Rpc.FullScan(mapping.map(_._2))))
      val pathStream = for {
        ((src, dest), i) <- geny.Generator.from(mapping.zipWithIndex)
        (p, attrs) <- os.walk.stream.attrs(
          src,
          (p, attrs) => skipArr(i)(p, attrs.isDir),
          includeTarget = true
        )
      } yield p
      RemoteScanning(pathStream.toSet)
  })

  case class RemoteScanning(changedPaths: Set[os.Path]) extends State({
    case SyncActor.Events(paths) => RemoteScanning(changedPaths ++ paths)
    case SyncActor.Receive(Response.Scanned(pathLists)) =>
      val vfsArr = for(pathList <- pathLists) yield {
        val vfs = new Vfs[Signature](Signature.Dir(0))
        for((path, sig) <- pathList) Vfs.updateVfs(path, sig, vfs)
        vfs
      }

      val scanned = for{
        (pathList, root) <- pathLists.zip(mapping.map(_._1))
        (path, sig) <- pathList
      } yield root / path
      executeSync(changedPaths ++ scanned, vfsArr)
  })

  case class Waiting(vfsArr: Seq[Vfs[Signature]]) extends State({
    case SyncActor.Events(paths) => executeSync(paths.toSet, vfsArr)
    case SyncActor.Receive(Response.Ack()) => Waiting(vfsArr) // do nothing
    case SyncActor.Debounced(debounceToken2) => Waiting(vfsArr) // do nothing
  })


  def executeSync(changedPaths: Set[os.Path], vfsArr: Seq[Vfs[Signature]]): State = {
    val buffer = new Array[Byte](Util.blockSize)

    // We need to .distinct after we convert the strings to paths, in order
    // to ensure the inputs are canonicalized and don't have meaningless
    // differences such as trailing slashes
    val allEventPaths = changedPaths.toSeq.sortBy(_.toString)
    logger("SYNC EVENTS", allEventPaths)

    val failed = mutable.Set.empty[os.Path]
    for (((src, dest), i) <- mapping.zipWithIndex) {
      val eventPaths = allEventPaths.filter(p =>
        p.startsWith(src) && !skipArr(i)(p, true)
      )

      logger("SYNC BASE", eventPaths.map(_.relativeTo(src).toString()))

      val exitCode = for {
        _ <- if (eventPaths.isEmpty) Left(NoOp: ExitCode) else Right(())
        _ <- updateSkipPredicate(
          eventPaths, skipper, vfsArr(i), src, buffer, logger,
          signatureTransformer, skipArr(i) = _
        )
        res <- synchronizeRepo(
          logger, vfsArr(i), skipArr(i), src, dest,
          agentReadWriter, buffer, eventPaths, signatureTransformer
        )
      } yield res

      exitCode match {
        case Right((streamedByteCount, changedPaths)) =>
        //                  allChangedPaths.appendAll(changedPaths)
        //                  allSyncedBytes += streamedByteCount
        case Left(NoOp) => // do nothing
        case Left(SyncFail(value)) =>
//          changedPaths.appendAll(eventPaths)
          val x = new StringWriter()
          val p = new PrintWriter(x)
          value.printStackTrace(p)
          logger("SYNC FAILED", x.toString)
          failed.addAll(eventPaths)
      }
    }

    if (failed.nonEmpty) this.send(SyncActor.Events(failed.toSet))
    Waiting(vfsArr)
  }
}

object DebounceActor{
  sealed trait Msg[T]
  case class Wrapped[T](value: T) extends Msg[T]
  case class Fire[T](items: Seq[T]) extends Msg[T]
}
class DebounceActor[T]
                   (handle: Seq[T] => Unit,
                    debounceMillis: Int)
                   (implicit ac: ActorContext) extends BatchActor[DebounceActor.Msg[T]]{
  def runBatch(items: Seq[DebounceActor.Msg[T]]) = {
    val values = items
      .flatMap{
        case DebounceActor.Wrapped(v) => Seq(v)
        case DebounceActor.Fire(vs) => vs
      }

    if (items.exists(_.isInstanceOf[DebounceActor.Fire[T]])){
      handle(values)
    }else{
      Thread.sleep(debounceMillis)
      this.send(DebounceActor.Fire(values))
    }
  }
}
/**
  * The Syncer class instances contain all the stateful, close-able parts of
  * the syncing logic: event queues, threads, filesystem watchers, etc. All the
  * stateless call-and-forget logic is pushed into static methods on the Syncer
  * companion object
  */
class Syncer(agent: AgentApi,
             mapping: Seq[(os.Path, os.RelPath)],
             skipper: Skipper,
             debounceMillis: Int,
             logger: Logger,
             signatureTransformer: (os.RelPath, Signature) => Signature)
            (implicit ac: ActorContext) extends AutoCloseable{

  private[this] val watcher = System.getProperty("os.name") match{
    case "Linux" =>
      new WatchServiceWatcher(
        mapping.map(_._1),
        events => debouncer.send(DebounceActor.Wrapped(SyncActor.Events(events))),
        logger
      )
    case "Mac OS X" =>
      new FSEventsWatcher(
        mapping.map(_._1),
        events => debouncer.send(DebounceActor.Wrapped(SyncActor.Events(events))),
        logger,
        0.05
      )
  }

  val syncer: SyncActor = new SyncActor(
    for ((src, dest) <- mapping.toArray) yield skipper.initialize(src),
    agentReadWriter,
    mapping,
    logger,
    signatureTransformer,
    skipper,
    Executors.newSingleThreadScheduledExecutor()
  )
  val agentReadWriter: AgentReadWriteActor = new AgentReadWriteActor(agent, syncer)

  val debouncer = new DebounceActor[SyncActor.Events](
    eventLists => syncer.send(SyncActor.Events(eventLists.flatMap(_.paths).toSet)),
    debounceMillis
  )


  val agentLoggerThread = new Thread(() => {
    while (try {
      val str = agent.stderr.readLine()
      if (str != null) logger.write(ujson.read(str).str)
      true
    } catch{
      case e: java.io.EOFException => false
      case e: java.io.IOException => false
    }) ()
  })

  val watcherThread = new Thread(() => watcher.start())

  var running = false
  def start() = {
    running = true
    agent.start()
    Syncer.spawnReaderThread(
      agent,
      buf => agentReadWriter.send(AgentReadWriteActor.Receive(buf)),
      () => agentReadWriter.send(AgentReadWriteActor.ReadRestarted())
    )
    agentLoggerThread.start()
    watcherThread.start()
    syncer.send(SyncActor.Scan())

  }

  def close() = {
    running = false
    watcher.close()
    agent.destroy()
  }
}

object Syncer{
  def spawnReaderThread(agent: AgentApi, out: Array[Byte] => Unit, restart: () => Unit) = {
    new Thread(() => {
      while(try{
        val s = agent.stdout.readBoolean()
        val n = agent.stdout.readInt()
        val buf = new Array[Byte](n)
        agent.stdout.readFully(buf)
        out(buf)
        true
      }catch{
        case e: java.io.EOFException =>
          restart()
          false
      })()
    }).start()
  }
  class VfsRpcClient(client: AgentReadWriteActor, stateVfs: Vfs[Signature], logger: Logger){
    def apply[T <: Action with Rpc: default.Writer](p: T) = {
      try {
        client.send(AgentReadWriteActor.Send(p))
        Vfs.updateVfs(p, stateVfs)
      } catch {
        case NonFatal(ex) => logger.info("Connection", s"Message cannot be sent $ex")
      }
    }
  }

  def updateSkipPredicate(eventPaths: Seq[os.Path],
                          skipper: Skipper,
                          vfs: Vfs[Signature],
                          src: os.Path,
                          buffer: Array[Byte],
                          logger: Logger,
                          signatureTransformer: (os.RelPath, Signature) => Signature,
                          setSkip: ((os.Path, Boolean) => Boolean) => Unit) = {
    val allModifiedSkipFiles = for{
      p <- eventPaths
      pathToCheck <- skipper.checkReset(p)
      newSig =
        if (!os.exists(pathToCheck)) None
        else Signature.compute(pathToCheck, buffer, os.FileType.Dir).map(signatureTransformer(pathToCheck.relativeTo(src), _))
      oldSig = vfs.resolve(pathToCheck.relativeTo(src)).map(_.value)
      if newSig != oldSig
    } yield (pathToCheck, oldSig, newSig)

    if (allModifiedSkipFiles.isEmpty) Right(())
    else{
      logger.info(
        "Gitignore file changed, re-scanning repository",
        allModifiedSkipFiles.head._1.toString
      )
      logger("SYNC GITIGNORE", allModifiedSkipFiles)
      restartOnFailure{setSkip(skipper.initialize(src))}
    }
  }

  /**
    * Represents the various ways a repo synchronization can exit early.
    */
  sealed trait ExitCode

  /**
    * Something failed with an exception, and we want to re-try the sync
    */
  case class SyncFail(value: Exception) extends ExitCode

  /**
    * There was nothing to do so we stopped.
    */
  case object NoOp extends ExitCode

  def synchronizeRepo(logger: Logger,
                      vfs: Vfs[Signature],
                      skip: (os.Path, Boolean) => Boolean,
                      src: os.Path,
                      dest: os.RelPath,
                      client: AgentReadWriteActor,
                      buffer: Array[Byte],
                      eventPaths: Seq[Path],
                      signatureTransformer: (os.RelPath, Signature) => Signature): Either[ExitCode, (Long, Seq[os.Path])] = {
    for{
      signatureMapping <- restartOnFailure(
        computeSignatures(eventPaths, buffer, vfs, skip, src, logger, signatureTransformer)
      )

      _ = logger("SYNC SIGNATURE", signatureMapping.map{case (p, local, remote) => (p.relativeTo(src), local, remote)})

      sortedSignatures = sortSignatureChanges(signatureMapping)

      filteredSignatures = sortedSignatures.filter{case (p, lhs, rhs) => lhs != rhs}

      _ <- if (filteredSignatures.nonEmpty) Right(()) else Left(NoOp)

      _ = logger.info(s"${filteredSignatures.length} paths changed", s"$src")

      vfsRpcClient = new VfsRpcClient(client, vfs, logger)

      _ = Syncer.syncMetadata(vfsRpcClient, filteredSignatures, src, dest, logger)

      streamedByteCount <- restartOnFailure(
        streamAllFileContents(logger, vfs, vfsRpcClient, src, dest, filteredSignatures)
      )
    } yield (streamedByteCount, filteredSignatures.map(_._1))
  }

  def sortSignatureChanges(sigs: Seq[(os.Path, Option[Signature], Option[Signature])]) = {
    sigs.sortBy { case (p, local, remote) =>
      (
        // First, sort by how deep the path is. We want to operate on the
        // shallower paths first before the deeper paths, so folders can be
        // created before their contents is written.
        p.segmentCount,
        // Next, we want to perform the deletions before any other operations.
        // If a file is renamed to a different case, we must make sure the old
        // file is deleted before the new file is written to avoid conflicts
        // on case-insensitive filesystems
        local.isDefined,
        // Lastly, we sort by the stringified path, for neatness
        p.toString
      )
    }
  }

  def restartOnFailure[T](t: => T)  = {
    try Right(t)
    catch{
      case e: RpcException => throw e
      case e: Exception => Left(SyncFail(e))
    }
  }

  def streamAllFileContents(logger: Logger,
                            stateVfs: Vfs[Signature],
                            client: VfsRpcClient,
                            src: Path,
                            dest: os.RelPath,
                            signatureMapping: Seq[(Path, Option[Signature], Option[Signature])]) = {
    val total = signatureMapping.length
    var byteCount = 0L
    for (((p, Some(Signature.File(_, blockHashes, size)), otherSig), n) <- signatureMapping.zipWithIndex) {
      val segments = p.relativeTo(src)
      val (otherHashes, otherSize) = otherSig match {
        case Some(Signature.File(_, otherBlockHashes, otherSize)) => (otherBlockHashes, otherSize)
        case _ => (Nil, 0L)
      }
      logger("SYNC CHUNKS", segments)
      byteCount += streamFileContents(
        logger,
        client,
        stateVfs,
        dest,
        p,
        segments,
        blockHashes,
        otherHashes,
        size,
        otherSize,
        n,
        total
      )
    }
    byteCount
  }

  def syncMetadata(client: VfsRpcClient,
                   signatureMapping: Seq[(os.Path, Option[Signature], Option[Signature])],
                   src: os.Path,
                   dest: os.RelPath,
                   logger: Logger): Unit = {
    logger.apply("SYNC META", signatureMapping)
    val total = signatureMapping.length
    for (((p, localSig, remoteSig), i) <- signatureMapping.zipWithIndex) {
      val segments = p.relativeTo(src)
      logger.progress(s"Syncing path [$i/$total]", segments.toString())
      (localSig, remoteSig) match {
        case (None, _) =>
          val rpc = Rpc.Remove(dest, segments)
          client(rpc)
        case (Some(Signature.Dir(perms)), remote) =>
          remote match {
            case None =>
              client(Rpc.PutDir(dest, segments, perms))
            case Some(Signature.Dir(remotePerms)) =>
              client(Rpc.SetPerms(dest, segments, perms))
            case Some(_) =>
              client(Rpc.Remove(dest, segments))
              client(Rpc.PutDir(dest, segments, perms))
          }

        case (Some(Signature.Symlink(target)), remote) =>
          remote match {
            case None =>
              client(Rpc.PutLink(dest, segments, target))
            case Some(_) =>
              client(Rpc.Remove(dest, segments))
              client(Rpc.PutLink(dest, segments, target))
          }
        case (Some(Signature.File(perms, blockHashes, size)), remote) =>
          if (remote.exists(!_.isInstanceOf[Signature.File])) client(Rpc.Remove(dest, segments))

          remote match {
            case Some(Signature.File(otherPerms, otherBlockHashes, otherSize)) =>
              if (perms != otherPerms) client(Rpc.SetPerms(dest, segments, perms))

            case _ => client(Rpc.PutFile(dest, segments, perms))
          }
      }
    }
  }

  def streamFileContents(logger: Logger,
                         client: VfsRpcClient,
                         stateVfs: Vfs[Signature],
                         dest: os.RelPath,
                         p: Path,
                         segments: os.RelPath,
                         blockHashes: Seq[Bytes],
                         otherHashes: Seq[Bytes],
                         size: Long,
                         otherSize: Long,
                         fileIndex: Int,
                         fileTotalCount: Int): Int = {
    val byteArr = new Array[Byte](Util.blockSize)
    val buf = ByteBuffer.wrap(byteArr)
    var byteCount = 0
    Util.autoclose(os.read.channel(p)) { channel =>
      for {
        i <- blockHashes.indices
        if i >= otherHashes.length || blockHashes(i) != otherHashes(i)
      } {
        val hashMsg = if (blockHashes.size > 1) s" $i/${blockHashes.length}" else ""
        logger.progress(
          s"Syncing file chunk [$fileIndex/$fileTotalCount$hashMsg]",
          segments.toString()
        )
        buf.rewind()
        channel.position(i * Util.blockSize)
        var n = 0
        while ( {
          if (n == Util.blockSize) false
          else channel.read(buf) match {
            case -1 => false
            case d =>
              n += d
              true
          }
        }) ()
        byteCount += n
        client(
          Rpc.WriteChunk(
            dest,
            segments,
            i * Util.blockSize,
            new Bytes(if (n < byteArr.length) byteArr.take(n) else byteArr),
            blockHashes(i)
          )
        )
      }
    }

    if (size != otherSize) client(Rpc.SetSize(dest, segments, size))
    byteCount
  }

  def computeSignatures(eventPaths: Seq[Path],
                        buffer: Array[Byte],
                        stateVfs: Vfs[Signature],
                        skip: (os.Path, Boolean) => Boolean,
                        src: os.Path,
                        logger: Logger,
                        signatureTransformer: (os.RelPath, Signature) => Signature)
    : Seq[(os.Path, Option[Signature], Option[Signature])] = {

    eventPaths.zipWithIndex.map { case (p, i) =>
      logger.progress(
        s"Scanning local folder [$i/${eventPaths.length}]",
        p.relativeTo(src).toString()
      )

      Tuple3(
        p,
        if (!os.exists(p, followLinks = false) ||
            // Existing under a differently-cased name counts as not existing.
            // The only way to reliably check for a mis-cased file on OS-X is
            // to list the parent folder and compare listed names
            !os.list(p / os.up).map(_.last).contains(p.last)) None
        else {
          val attrs = Files.readAttributes(p.wrapped, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
          val fileType =
            if (attrs.isRegularFile) os.FileType.File
            else if (attrs.isDirectory) os.FileType.Dir
            else if (attrs.isSymbolicLink) os.FileType.SymLink
            else if (attrs.isOther) os.FileType.Other
            else ???

          Signature
            .compute(p, buffer, fileType)
            .map(signatureTransformer(p.relativeTo(src), _))
        },
        stateVfs.resolve(p.relativeTo(src)).map(_.value)
      )
    }

  }
}
