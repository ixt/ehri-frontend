// Comment to get more information during initialization
logLevel := Level.Warn

// The Typesafe repository 
resolvers += "Typesafe repository" at "http://repo.typesafe.com/typesafe/releases/"

//resolvers += "Typesafe Snapshots" at "http://repo.typesafe.com/typesafe/snapshots/"

// Use the Play sbt plugin for Play projects
addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.6.21")

addSbtPlugin("com.typesafe.sbt" % "sbt-less" % "1.0.6")

addSbtPlugin("com.typesafe.sbt" % "sbt-uglify" % "2.0.0")

addSbtPlugin("com.typesafe.sbt" % "sbt-digest" % "1.1.3")

addSbtPlugin("com.typesafe.sbt" % "sbt-gzip" % "1.0.2")

addSbtPlugin("net.ground5hark.sbt" % "sbt-concat" % "0.2.0")

// Excluded because too slow for workable development
//addSbtPlugin("net.ground5hark.sbt" % "sbt-css-compress" % "0.1.4")
