ThisBuild / organization := "com.hubburu"
ThisBuild / organizationName := "hubburu"
ThisBuild / organizationHomepage := Some(url("https://www.hubburu.com"))

ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/https://github.com/hubburu/sangria-plugin"),
    "scm:git@github.com:hubburu/sangria-plugin.git"
  )
)

ThisBuild / developers := List(
  Developer(
    id = "peternycander",
    name = "Peter Nycander",
    email = "peter.nycander@gmail.com",
    url = url("https://www.peternycander.com")
  )
)

ThisBuild / description := "A middleware for integrating Hubburu with Sangria (Scala) GraphQL"
ThisBuild / licenses := List(
  "MIT" -> new URL(
    "https://github.com/hubburu/sangria-plugin/blob/master/LICENSE"
  )
)
ThisBuild / homepage := Some(url("https://github.com/hubburu/sangria-plugin"))

// Remove all additional repository other than Maven Central from POM
ThisBuild / pomIncludeRepository := { _ => false }

ThisBuild / publishTo := {
  val nexus = "https://s01.oss.sonatype.org/"
  if (isSnapshot.value)
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else Some("releases" at nexus + "service/local/staging/deploy/maven2")
}

ThisBuild / publishMavenStyle := true

ThisBuild / versionScheme := Some("early-semver")
