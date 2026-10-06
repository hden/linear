# Development

## Container

Copy `.env.example` to `.env` and configure [authentication](authentication.md).

```sh
docker compose up --build app
```

Inspect configuration or start a REPL:

```sh
docker compose run --rm app clojure -M:duct --show --main
docker compose run --rm app bb repl
```

## macOS host

Use Java 25 and SQLite. Register the JDK with jenv and extract the SlateDB library:

```sh
brew install openjdk@25
jenv add "$(brew --prefix openjdk@25)/libexec/openjdk.jdk/Contents/Home"
sh scripts/init.sh
```

Configure `.env` as above, then run `direnv allow`. `.envrc` sets
`JAVA_TOOL_OPTIONS` so the JVM can find SQLite and SlateDB.

[Release maintenance](releases.md)
