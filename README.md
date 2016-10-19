#throttle [![Build Status](https://travis-ci.org/jamespedwards42/throttle.svg)](https://travis-ci.org/jamespedwards42/throttle) [![JCenter](https://api.bintray.com/packages/jamespedwards42/libs/throttle/images/download.svg) ](https://bintray.com/jamespedwards42/libs/throttle/_latestVersion) [![License](http://img.shields.io/badge/license-Apache--2-blue.svg?style=flat) ](http://www.apache.org/licenses/LICENSE-2.0)

###Usage

######Submit two tasks per second:

```java
final Throttle rateLimiter = Throttle.create(2.0); // rate is "2 permits per second"

void submitTasks(List<Runnable> tasks, Executor executor) {
    for (Runnable task : tasks) {
      throttle.acquire(); // may wait
      executor.execute(task);
    }
}
```

######Cap data stream to 5kb per second:

```java
final Throttle throttle = Throttle.create(5000.0); // rate = 5000 permits per second

void submitPacket(byte[] packet) {
    throttle.acquire(packet.length);
    networkService.send(packet);
}
```

###Changes From [Guava RateLimiter](https://github.com/google/guava/blob/master/guava/src/com/google/common/util/concurrent/RateLimiter.java)
* Nanosecond instead of microsecond accuracy.
* Factoring out an interface class (Throttle.java) from the base abstract class.
* Remove the need for any non-core-Java classes outside of the original RateLimiter and SmoothRateLimiter classes.
* Remove the need for a SleepingStopwatch or similar class instance.
* Use of volatile variables to prevent stale reads under concurrent access.

###Dependency Management
####Gradle
```groovy
repositories {
   jcenter()
}

dependencies {
   compile 'com.fabahaba:throttle:+'
}
```
