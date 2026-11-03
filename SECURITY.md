# Security

## Reporting a vulnerability

Please report it privately through GitHub: **Security > Report a vulnerability** on this repository. Don't open a public issue. Include the steps to reproduce, the TV model and Android version, and the app versions (Settings > About & keys on the TV). We aim to acknowledge reports within 7 days.

## What's in scope

- The TV apps: Friday News (`ai.futurespeak.friday.tv.news`).

Especially interesting: any way a feed or article could run code, reach a host that is not listed, or get past the kids filter.

## How it handles data

- News holds no keys. Friday's voice and Ask Friday go through the add-on contract, which answers only add-ons the owner enabled.
- News fetches only from the hosts listed in Sources.java, over HTTPS.

## Out of scope

- Third-party AI providers' own services, third-party apps we link to, and the TV maker's firmware.
- Anything that needs someone already able to run adb on your TV. With debugging on, a paired computer controls the TV; switch Wireless debugging off when you're done (the wizard reminds you).
