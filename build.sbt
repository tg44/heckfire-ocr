name := "heckfire-ocr"

version := "0.1"

scalaVersion := "2.12.7"


libraryDependencies += "net.sourceforge.tess4j" % "tess4j" % "4.0.0"

libraryDependencies += "net.katsstuff" %% "ackcord-core"                 % "0.10.0"
libraryDependencies += "net.katsstuff" %% "ackcord-commands-core"   % "0.10.0"

enablePlugins(JavaAppPackaging)

dockerBaseImage := "openjdk:8"
