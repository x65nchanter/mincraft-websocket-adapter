# Minecraft Websocket Bridge

## Setup

For setup instructions, please see the [Fabric Documentation page](https://docs.fabricmc.net/develop/getting-started/creating-a-project#setting-up) related to the IDE that you are using.

## License

This template is available under the CC0 license. Feel free to learn from it and incorporate it in your own projects.

podman machine ssh
sudo dnf install -y socat # если её еще нет в виртуалке
socat TCP-LISTEN:64342,fork TCP:10.0.2.2:64342 &
