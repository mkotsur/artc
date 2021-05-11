# Artc [![Build Status](https://circleci.com/gh/mkotsur/artc.svg?&style=shield&circle-token=22c35ff0e9c28f61d483d178f8932c928e47dfc2)](https://circleci.com/gh/mkotsur/artc) [![Maven Central](https://img.shields.io/maven-central/v/io.github.mkotsur/artc_2.13.svg)](http://search.maven.org/#search%7Cga%7C1%7Cg%3A%22io.github.mkotsur%22) [![Known Vulnerabilities](https://snyk.io/test/github/mkotsur/artc/badge.svg?targetFile=build.sbt)](https://snyk.io/test/github/mkotsur/artc?targetFile=build.sbt) <a href="https://typelevel.org/cats/"><img src="https://user-images.githubusercontent.com/202410/45928046-6dbfd980-bf3d-11e8-8693-c1285dae03ce.png" height="25px" alt="Cats friendly" /></a>

Artc (pronounced art·sy) is an Active Read-Through Cache for cats-effect programs. Its primary goal is to allow developers to write as little code as possible to solve a common problem of reading data from services with high latency.

## Motivation
Sometimes you need to integrate with slow services. This is rarely a pleasant problem to solve, but it doesn't have to take weeks to solve and destroy your development experience. Artc is here to help! 

## Main features
* Non-blocking read-through;
* Active content synchronization;
* Automatic refresh rate adjustment;
* Use it as a library or a REST microservice (soon).

<img src="/docs/artc.png" alt="How Artc works" width="640" />

## Example

```scala
import io.github.mkotsur.artc.Cache
import cats.effect.{ExitCode, IO, IOApp}

// 1. Configure
case class User(name: String)

val settings: Cache.Settings  = ???
val readSource: IO[List[User]] = ???

// 2. Create as resource
val cacheR: Resource[IO, Cache[List[User]]] = 
  Cache.create(settings, readSource)

// 3. Use
for {
  latest <- cacheR.use(cache => cache.latest)
  _ <- IO(println(v))   
  _ <- updateSharesFiber.join
} yield ExitCode.Success
```

## Adding to your project

Scala versions supported: 2.13.x.

```sbt
libraryDependencies += "io.github.mkotsur" %% "artc" % {latest-version}
```