# Platform-up-2-date

## Build for development

Simplest way to build and run the whole application is using Docker. An unfortunate pre-requisite, as of now, is that the backend application has to be build before hand.

Navigate to the backend folder and build using gradle:

```bash
$ cd backend
$ ./gradlew build
```

Then, go back to the root folder and run the docker compose:

```bash
$ cd ..
$ docker compose up -d
```

Verify that the two containers are up and running:

```bash
$ docker ps
```

Then go to localhost:3000 using your favorite browser, et voila!
