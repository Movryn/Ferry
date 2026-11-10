# Ferry

Ferry connects your Minecraft server's chat to a Discord channel, a [Fluxer](https://fluxer.app) channel, or both.

I wrote it because the Fluxer plugins I tried kept dropping off after a restart and only worked with fluxer.app.
Ferry reconnects by itself, picks up anything it missed while it was gone, and works with self-hosted Fluxer too.

## What it does

- Chat goes both ways. In Discord and Fluxer, players show up with their own name and face.
- Joins, leaves, deaths, advancements and server start/stop are posted as embeds. You can switch any of them to
  plain text, turn them off, or change the wording and colour.
- Links and attachments from Discord and Fluxer are clickable in game.
- With Discord and Fluxer both on, the two channels can see each other's messages too.
- `!online` in the channel lists who's playing.
- `!mc <command>` runs a console command, but only for the user IDs you list.
- Game chat can't ping `@everyone` or anyone else.

Right now Ferry runs on Spigot and Paper. Fabric, NeoForge and Forge versions are coming.

## Setting it up

1. Put `Ferry-Bukkit-x.x.x.jar` in your `plugins` folder and start the server once. It writes `plugins/Ferry/config.yml`.
2. Fill in the Discord part, the Fluxer part, or both (see below).
3. Run `/ferry reload`, then `/ferry status` to check it connected.

### Discord

1. Make an application at https://discord.com/developers/applications and add a bot to it.
2. On the Bot page, copy the token and turn on **Message Content Intent**.
3. Invite the bot with the **View Channels**, **Send Messages**, **Embed Links**, **Read Message History**,
   **Add Reactions** and **Manage Webhooks** permissions.
4. Turn on Developer Mode in Discord, right click your channel and copy its ID.
5. In `config.yml`, set `discord.enabled: true` and fill in the token and channel.

### Fluxer

1. Make a bot under **User Settings > Applications** in Fluxer and copy its token.
2. Invite it to your community and copy the channel ID.
3. In `config.yml`, set `fluxer.enabled: true`, fill in the token and channel, and set `instance` to `fluxer.com` or
   the address of your own Fluxer.

If the bot can manage webhooks, Ferry makes its own webhook in the channel. Without one, messages still go through,
they just come from the bot instead of the player.

## Player faces

By default, faces come from mc-heads.net, which works for normal Minecraft accounts. If your server uses its own
login server (authlib-injector, Drasl and so on), point `avatar-url` at something that knows your players' skins.
`{uuid}` and `{name}` are filled in for you.

## Commands

| Command | What it does |
|---|---|
| `/ferry status` | Shows whether each connection is up |
| `/ferry reload` | Reloads `config.yml` and reconnects |

Both need `ferry.admin`, which ops have by default.

## Building

```
./gradlew build
```

The plugin ends up in `bukkit/build/libs`. You need Java 17 or newer to build it. The plugin itself runs on Java 8 and up.

## License

MIT. See [LICENSE](LICENSE).
