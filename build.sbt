name := "Hubburu Sangria middleware"
version := "0.0.4"

description := "A middleware to integrate Hubburu with Sangria"

ThisBuild / crossScalaVersions := Seq("2.13.6")
ThisBuild / scalaVersion := crossScalaVersions.value.last

scalacOptions ++= Seq("-deprecation", "-feature", "-Xsource:3")

libraryDependencies ++= Seq(
  "org.sangria-graphql" %% "sangria" % "2.1.6",
  "com.softwaremill.sttp.client3" %% "async-http-client-backend-future" % "3.4.1"
)
