import bintray.Keys._

name := "sbt-simple-server"

organization := "com.hanhuy.sbt"

version := "0.3"

scalacOptions ++= Seq("-deprecation","-Xlint","-feature")

libraryDependencies += "com.hanhuy.sbt" %% "bintray-update-checker" % "0.1"

buildInfoSettings

buildInfoPackage := "sbtsimpleserver"

sourceGenerators in Compile <+= buildInfo

sbtPlugin := true

// bintray
bintrayPublishSettings

repository in bintray := "sbt-plugins"

publishMavenStyle := false

licenses += ("MIT", url("http://opensource.org/licenses/MIT"))

bintrayOrganization in bintray := None
