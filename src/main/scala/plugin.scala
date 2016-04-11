package sbtsimpleserver

import java.io.BufferedReader
import java.net.{InetAddress, ServerSocket}
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{BlockingQueue, Executors, LinkedBlockingQueue}

import sbt.{Future => _, _}
import BasicCommandStrings.{Shell, ShellDetailed}
import Keys.commands
import BasicKeys._
import Keys.onLoad
import Keys.onUnload

import scala.concurrent._
import scala.util.Try

object SimpleServerPlugin extends AutoPlugin {

  case class ServerCommand[A](command: Option[String], result: Boolean => A = (b: Boolean) => ())

  case class ServerData(queue: BlockingQueue[ServerCommand[_]],
                        shell: State => ServerCommand[_],
                        lock: Lock,
                        running: AtomicBoolean,
                        server: Option[Thread])

  val serverSetup = AttributeKey[ServerData]("sbt-server-setup", "internal server data")
  val serverResult = AttributeKey[ServerCommand[_]]("sbt-server-result", "internal current command server data")

  override def trigger = allRequirements
  override def requires = plugins.JvmPlugin

  val ShellCommand = "server-" + Shell
  val FailedShellCommand = ShellCommand + "-failed"

  override def buildSettings = Seq(
    commands ++= Seq(serverAndShell, failedServerAndShell)
  )

  override def globalSettings = Seq(
    onLoad := onLoad.value andThen { s =>
      val (xs, ys) = s.remainingCommands.span(_ != "iflast shell")
      if (ys.headOption.exists(_ == "iflast shell") && s.get(serverSetup).isEmpty) {
        val s2 = s.copy(remainingCommands = xs ++ Seq("iflast " + ShellCommand) ++ ys.drop(1))
        val lock = new Lock()
        val queue = new LinkedBlockingQueue[ServerCommand[_]](10)
        val running = new AtomicBoolean(true)
        val server = startNetworkRepl(s, queue, lock, running)
        val sd = ServerData(queue, startShellRepl(s2, queue, lock, running), lock, running, server)
        s2.put(serverSetup, sd)
      } else s
    },
    onUnload := onUnload.value andThen { s =>
      s.get(serverSetup) foreach { sd =>
        sd.running.set(false)
        sd.lock.release()
        sd.server.foreach(_.interrupt())
      }
      s.remove(serverSetup)
    }
  )

  def serverAndShell = Command.command(ShellCommand, Help.more(Shell, ShellDetailed)) { s =>
    s.get(serverResult).foreach(_.result(true))
    s get serverSetup map { sd =>

      sd.lock.release()
      val resultObject = sd.shell(s)
      val read = resultObject.command
      sd.lock.acquire()

      read match {
        case Some(line) =>
          val newState = s.put(serverResult, resultObject).copy(
            onFailure = Some(FailedShellCommand),
            remainingCommands = line +: ShellCommand +: s.remainingCommands).setInteractive(true)
          if (line.trim.isEmpty) newState else newState.clearGlobalLog
        case None => s.setInteractive(false)
      }
    } getOrElse s
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
  def startNetworkRepl(s: State, queue: BlockingQueue[ServerCommand[_]], lock: Lock, running: AtomicBoolean): Option[Thread] = {
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
                        val res = Await.result(promise.future, concurrent.duration.Duration.Inf)
                        o.write((if (res) 0 else 1).toString.getBytes(IO.utf8))
                        o.flush()
                      }
                    }
                  }
                } finally {
                  sock.close()
                }
              }
            } catch {
              case e: InterruptedException =>
            }
          }
        }
      }
      val t = new Thread(SocketReader, "SBT server network reader")
      t.start()
      t
    }

  }
}
