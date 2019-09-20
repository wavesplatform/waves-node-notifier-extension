# Mining notifier extension for Waves Node

## What can do

- when the node starts, it sends message:
  - if mining disabled in config
  - a generating balance is less than 1000 Waves
  - the miner account has a smart contract
- notifies if the node mined block and its reward
- notifies about incoming Waves or MRT payments
- notifies about changes of leased volume.

## How to install:
1. download `mining-notifier-0.3.3.jar` to `/usr/share/waves/lib/`:
```
wget https://github.com/msmolyakov/waves-node-notifier-extension/releases/download/v0.3.3/mining-notifier-0.3.3.jar -P /usr/share/waves/lib/
```
2. download official `scalaj-http_2.12-2.4.2.jar` from Maven Central to `/usr/share/waves/lib/`:
```
wget https://repo1.maven.org/maven2/org/scalaj/scalaj-http_2.12/2.4.2/scalaj-http_2.12-2.4.2.jar -P /usr/share/waves/lib/
```
3. add to `/etc/waves/waves.conf` (or `local.conf`):
```
waves.extensions = [
  "im.mak.notifier.MinerNotifierExtension"
]
mining-notifier.webhook {
  # url = "https://example.com/webhook/1234567890" # SPECIFY YOUR ENDPOINT
  # body = """Mainnet: %s"""
}
```
For example, for notifications you can use Telegram bot https://t.me/bullhorn_bot from https://integram.org/ team.
