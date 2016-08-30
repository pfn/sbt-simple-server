# sbt-simple-server

Simple SBT server plugin

## Usage

* In `project/plugins.sbt`
  `addSbtPlugin("com.hanhuy.sbt" % "sbt-simple-server" % "0.4")`
* `disablePlugins(SimpleServerPlugin)` if desired, otherwise it will be
  automatically enabled
* Run and use SBT like normal
* Run the `sbt-client.py` script from the project you want to connect to
  remotely, anything that can be typed into an sbt repl can be typed into
  passed as an argument to `sbt-client.py`, add quotes if necessary.

### How it works

Inspired by Eugene's writeup at http://eed3si9n.com/sbt-server-reboot, I
decided to implement this personally without having to wait for anything
to be integrated into SBT. The general idea is the same, except this is
even further underengineered.

* Replaces the `shell` command with `server-shell`
* Runs a thread to loop over shell input, and runs a thread to read a
  network port assigned to the project (determined by taking the first 2
  bytes of the canonical path hash until one is found that is over 1024)
* Reads input from network and repl and throws it into a blocking queue
* Every time the `server-shell` command is executed, a command is popped
  off the queue, polling until one is available if necessary.
* Commands that come from the network have a result status passed to them
  to indicate whether they succeeded or failed, (0 for success,
  1 for failure, 2 for command in-flight)

