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

## API

We're using a contract-first approach between the frontend and backend, where the API is first defined in a json-schema using [API Curio](https://www.apicur.io/), then source files generated for both frontend and backend respectively using [OpenAPI Generator](https://github.com/OpenAPITools/openapi-generator/blob/master/modules/openapi-generator-gradle-plugin/README.adoc). These generated files are to be viewed as immutable source files.

The API Curio studio can easily be hosted locally by running the following command from root:

```bash
$ docker compose -f "compose.apicurio.yml" up -d
```

then [open it up](http://localhost:8888) in your browser. The schemas does not persist, however, so make sure to download them before tearing down the containers.
