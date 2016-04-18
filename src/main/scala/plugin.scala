package sbtsimpleserver

import java.io.{BufferedReader, Closeable}
import java.net.{InetAddress, ServerSocket, SocketException}
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{BlockingQueue, Executors, LinkedBlockingQueue}

import sbt.{Future => _, _}
import BasicCommandStrings.{Shell, ShellDetailed}
import Keys.commands
import BasicKeys._
import Keys.onLoad
import Keys.onUnload
import com.hanhuy.sbt.bintray.UpdateChecker

import scala.concurrent._
import scala.util.Try

object SimpleServerPlugin extends AutoPlugin {

  case class ServerCommand[A](command: Option[String], result: Boolean => A = (b: Boolean) => ())

  case class ServerData(queue: BlockingQueue[ServerCommand[_]],
                        shell: State => ServerCommand[_],
                        lock: Lock,
                        running: AtomicBoolean,
                        server: Option[Closeable])

  val updateCheck = TaskKey[Unit]("update-check", "Check for a new version of the plugin")
  val serverSetup = AttributeKey[ServerData]("sbt-server-setup", "internal server data")
  val serverResult = AttributeKey[ServerCommand[_]]("sbt-server-result", "internal current command data")

  override def trigger = allRequirements
  override def requires = plugins.JvmPlugin

  val ShellCommand = "server-" + Shell
  val FailedShellCommand = ShellCommand + "-failed"

  override def buildSettings = Seq(
    commands    ++= Seq(serverAndShell, failedServerAndShell),
    updateCheck  := {
      val log = Keys.streams.value.log
      UpdateChecker("pfn", "sbt-plugins", "sbt-simple-server") {
        case Left(t) =>
          log.debug("Failed to load version info: " + t)
        case Right((versions, current)) =>
          log.debug("available versions: " + versions)
          log.debug("current version: " + BuildInfo.version)
          log.debug("latest version: " + current)
          if (versions(BuildInfo.version)) {
            if (BuildInfo.version != current) {
              log.warn(
                s"UPDATE: A newer sbt-simple-server is available:" +
                  s" $current, currently running: ${BuildInfo.version}")
            }
          }
      }
    }
  )

  override def globalSettings = Seq(
    onLoad := onLoad.value andThen { s =>
      s.copy(remainingCommands = s.remainingCommands.map {
        case "iflast shell" => s"iflast $ShellCommand"
        case "shell"        => ShellCommand
        case x              => x
      })
    } andThen { s =>
      Project.extract(s).runTask(updateCheck, s)._1
    },
    onUnload := onUnload.value andThen { s =>
      s.get(serverSetup) foreach { sd =>
        sd.running.set(false)
        sd.lock.release()
        sd.server.foreach(_.close())
      }
      s.remove(serverSetup)
    }
  )

  def serverAndShell = Command.command(ShellCommand, Help.more(Shell, ShellDetailed)) { s =>
    s.get(serverResult).foreach(_.result(true))
    val serverState = s.get(serverSetup).fold {
      val lock = new Lock()
      val queue = new LinkedBlockingQueue[ServerCommand[_]](10)
      val running = new AtomicBoolean(true)
      val sd = ServerData(queue,
        startShellRepl(s, queue, lock, running),
        lock, running,
        startNetworkRepl(s, queue, lock, running))
      s.put(serverSetup, sd)
    }(_ => s)
    serverState get serverSetup map { sd =>

      sd.lock.release()
      val resultObject = sd.shell(serverState)
      val read = resultObject.command
      sd.lock.acquire()

      read match {
        case Some(line) =>
          val newState = serverState.put(serverResult, resultObject).copy(
            onFailure = Some(FailedShellCommand),
            remainingCommands = line +: ShellCommand +: serverState.remainingCommands).setInteractive(true)
          if (line.trim.isEmpty) newState else newState.clearGlobalLog
        case None => serverState.setInteractive(false)
      }
    } getOrElse serverState
  }
  def failedServerAndShell = Command.command(FailedShellCommand, Help.more(Shell, ShellDetailed)) { s =>
    s.get(serverResult).foreach(_.result(false))
    s.remove(serverResult).copy(remainingCommands = ShellCommand +: s.remainingCommands)
  }

  def startShellRepl(s: State, queue: BlockingQueue[ServerCommand[_]], lock: Lock, running: AtomicBoolean): State => ServerCommand[_] = {
    val history = (s get historyPath) getOrElse Some(new File(s.baseDir, ".history"))
    val prompt: State => String = s get shellPrompt match { case Some(pf) => pf; case None => _ => "> " }

    object LineReader extends Runnable {
      var state = s
      override def run() = {
        while (running.get) {
          try {
            lock.synchronized {
              while (queue.size() > 0)
                lock.wait()
            }
            lock.acquire()
            try {
              if (running.get) {
                val read = new FullReader(history, state.combinedParser).readLine(prompt(state))
                queue.put(ServerCommand(read))
              }
            } finally {
              lock.release()
            }
          } catch { case e: InterruptedException => }
        }
      }
    }

    new Thread(LineReader, "SBT server shell reader").start()

    { st =>
      LineReader.state = st
      queue.take() // take anything that's put into the queue, not just shell repl input
    }
  }

  val PORT_MAX = (2 << 16) - 1
  def startNetworkRepl(s: State, queue: BlockingQueue[ServerCommand[_]], lock: Lock, running: AtomicBoolean): Option[Closeable] = {
    val hash = Hash(s.baseDir.getCanonicalPath)
    implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(4))
    val port = hash.zip(hash.tail).collectFirst {
      case (x, y) if (((x << 8) | y) & 0xffff) > 1024 => ((x << 8) | y) & 0xffff }
    port map { p =>
      val sock = (0 to PORT_MAX).toIterator.map(i =>
        Try(new ServerSocket((p + i) % PORT_MAX, 50, InetAddress.getLoopbackAddress)).toOption)
        .dropWhile(_.isEmpty).collectFirst { case Some(x) => x }
      val socket = sock.get
      s.log.info("SBT server listening on port " + socket.getLocalPort)
      val target = Project.extract(s).get(Keys.target)
      IO.write(target / "sbt-server-port", socket.getLocalPort.toString)
      object SocketReader extends Runnable {
        override def run() = {
          while (running.get) {
            try {
              val sock = socket.accept()
              Future {
                try {
                  Using.streamReader(sock.getInputStream, IO.utf8) { i =>
                    Using.bufferedOutputStream(sock.getOutputStream) { o =>
                      if (queue.size() > 0) {
                        o.write(2.toString.getBytes(IO.utf8))
                      } else {
                        val reader = new BufferedReader(i)
                        val read = reader.readLine()
                        val promise = Promise[Boolean]()
                        queue.put(ServerCommand(Option(read), promise.success))
                        lock.release()
                        val res = Await.result(promise.future, duration.Duration.Inf)
                        o.write((if (res) 0 else 1).toString.getBytes(IO.utf8))
                        o.flush()
                      }
                    }
                  }
                } finally {
                  sock.close()
                }
              }
            } catch { case e: SocketException => }
          }
          socket.close()
        }
      }
      new Thread(SocketReader, "SBT server network reader").start()
      socket
    }

  }
}
