import bintray.Keys._

name := "sbt-simple-server"

organization := "com.hanhuy.sbt"

version := "0.2-SNAPSHOT"

scalacOptions ++= Seq("-deprecation","-Xlint","-feature")

sbtPlugin := true

// bintray
bintrayPublishSettings

repository in bintray := "sbt-plugins"

publishMavenStyle := false

licenses += ("MIT", url("http://opensource.org/licenses/MIT"))

bintrayOrganization in bintray := None
