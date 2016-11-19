#throttle [![Build Status](https://travis-ci.org/client-side/throttle.svg)](https://travis-ci.org/client-side/throttle) [![JCenter](https://api.bintray.com/packages/client-side/clients/throttle/images/download.svg) ](https://bintray.com/client-side/libs/throttle/_latestVersion) [![License](http://img.shields.io/badge/license-Apache--2-blue.svg?style=flat) ](http://www.apache.org/licenses/LICENSE-2.0) [![codecov](https://codecov.io/gh/client-side/throttle/branch/master/graph/badge.svg)](https://codecov.io/gh/client-side/throttle)

>Provides a mechanism to limit the rate of access to a resource.

###Usage

A throttle instance distributes permits at a desired rate, blocking if necessary until a permit is available.

######Submit two tasks per second:

```java
final Throttle rateLimiter = Throttle.create(2.0); // 2 permits per second

void submitTasks(List<Runnable> tasks, Executor executor) {
  for (Runnable task : tasks) {
    throttle.acquire();
    executor.execute(task);
  }
}
```

######Cap data stream to 5kb per second:

```java
final Throttle throttle = Throttle.create(5000.0); // 5000 permits per second

void submitPacket(byte[] packet) {
  throttle.acquire(packet.length);
  networkService.send(packet);
}
```

###Changes From Guava Rate Limiter
* Nanosecond instead of microsecond accuracy.
* Factoring out an interface class, [Throttle](src/main/java/engineering/clientside/throttle/Throttle.java#L81), from the base abstract class.
* Remove the need for any non-core-Java classes outside of the original [RateLimiter](https://github.com/google/guava/blob/master/guava/src/com/google/common/util/concurrent/RateLimiter.java) and [SmoothRateLimiter](https://github.com/google/guava/blob/master/guava/src/com/google/common/util/concurrent/SmoothRateLimiter.java) classes.
* Remove the need for a [SleepingStopwatch](https://github.com/google/guava/blob/master/guava/src/com/google/common/util/concurrent/RateLimiter.java#L395) or similar class instance.
* Use of volatile variables to prevent stale reads under concurrent access.
* Guava provides rate limiters with either a "bursty" or "warm-up" behavior. Throttle provides only a single strict rate limiter implementation that will never exceed the desired rate limit over a one second period.

###Dependency Management
```groovy
repositories {
   jcenter()
}

dependencies {
   compile 'engineering.clientside:throttle:+'
}
```
