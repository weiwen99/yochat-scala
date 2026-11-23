# YoChat shell

Electron is only the desktop shell. It starts and stops the local JVM service, enforces a single instance, keeps the window on the local service origin, and forwards desktop notifications. It does not hold message or key state.

## Development

1. Build the local service and UI bundle:

   ```sh
   ./mill server.assembly
   ```

2. Start a relay once (the relay is a separate process):

   ```sh
   java -jar out/transport/assembly.dest/out.jar 7654
   ```

3. Start the desktop shell:

   ```sh
   cd shell
   npm install
   npm start
   ```

The shell looks for the server in this order:

- `YOCHAT_SERVER_JAR`
- `../out/server/assembly.dest/out.jar`
- `process.resourcesPath/server.jar`

`YOCHAT_RELAY` defaults to `127.0.0.1:7654`; `YOCHAT_PORT` defaults to `8787`.

The web surface is the same UI at `http://127.0.0.1:8787/`; the desktop surface adds the hidden-inset title bar, single-instance behavior, and native notifications through the preload bridge.

## Packaged layout

Put `server.jar` beside the Electron resources and set `YOCHAT_SERVER_JAR` only when using a non-default location. The data directory is `app.getPath("userData")/data`. The local service remains bound to loopback.

## Notification boundary

The renderer sends only a contact label and a generic body ("有一条新消息") to the main process. Message text is not sent to the operating system notification by default.
