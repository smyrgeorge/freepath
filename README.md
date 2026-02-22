# Freepath

> An information network that lives in your pocket and spreads through human contact.

---

## Table of Contents

- [The idea](#the-idea)
    - [The name](#the-name)
    - [Why not a social network?](#why-not-a-social-network)
- [You control what you share](#you-control-what-you-share)
- [How information travels](#how-information-travels)
    - [How devices talk to each other](#how-devices-talk-to-each-other)
- [Privacy and trust](#privacy-and-trust)
- [Content](#content)
    - [Deletion](#deletion)
- [Where we are](#where-we-are)
- [Get involved](#get-involved)

## The idea

Freepath is an information network that requires no internet, no servers, and no central authority of any kind.

Instead of routing your messages through a data center, Freepath works the way rumors and letters worked for thousands
of years: **person to person, device to device**.

When two phones running Freepath come near each other — at a coffee shop, on a bus, at a concert — they automatically
and silently exchange content. Posts, messages, updates. Things you wrote. Things people you trust wrote. The network is
the crowd itself.

Your phone becomes a node. You become the infrastructure.

### The name

**Free** — because there are no servers, no gatekeepers, no central authority that can grant or revoke access. The
network belongs to no one, which means it belongs to everyone.

**Path** — because information doesn't travel through cables or data centers. It travels through people. It follows the
routes that humans walk, the places they gather, the moments they cross paths. The path is physical, human, and alive.

The name is part of this concept and may change as the idea evolves.

### Why not a social network?

We deliberately avoid the term. Not because it's inaccurate, but because it carries too much baggage — timelines,
followers, likes, engagement loops, growth metrics. That's not what this is.

Freepath doesn't aim to compete with or replace the messaging apps and platforms you already use. It doesn't want to be
the next Twitter, or the next Signal. It sees itself as something different in nature: a decentralized layer for sharing
information through the very act of people being in the same place.

Think of it less as an app and more as a concept — an invisible, ownerless current that flows through human proximity.

## You control what you share

When two phones meet, nothing is exchanged without your consent. You choose the strategy that fits your needs:

- **Starred** — only content you've explicitly marked to propagate
- **Last N posts** — a rolling window of your most recent activity
- **Everything** — your full local store, for those who want to be active carriers
- **Something else entirely** — the model is open, and other strategies are possible

This means propagation itself is decentralized. What spreads through the network is the result of thousands of
individual human decisions — not a private algorithm running on a server somewhere, optimizing for engagement or
burying content without explanation. There is no invisible hand. What travels, travels because people chose to carry it.

## How information travels

Think of it like seeds carried by the wind — except the wind is people going about their lives.

You write a post. Your phone stores it. Later, you walk past someone else using Freepath. Your phones notice each other
and exchange what each is missing. That person goes home, walks past their neighbor, sits on a train. The post keeps
spreading — carried physically by humans, hopping from device to device without ever touching a server.

This is called **Store, Carry, Forward**. It's how delay-tolerant networks have operated in research for decades.
Freepath brings this idea to everyday communication.

There is no real-time guarantee. A post might reach someone in minutes, or hours, or days — depending on how connected
the human mesh is. But it will reach them. No algorithm decides otherwise. No moderator can suppress it at the source.
No company can deplatform you from a network that has no platform.

### How devices talk to each other

Freepath is designed to work over multiple short-range communication channels, depending on what the devices support:

- **Bluetooth** — the primary channel. Two phones near each other discover and exchange data automatically in the
  background, no interaction needed.
- **Local Wi-Fi** — when devices are on the same network (a home router, a hotspot, an office network), they can sync
  directly without touching the internet.
- **NFC** — for close-range, intentional transfers. Tap two phones together to exchange content instantly.
- **QR codes** — a screen-to-camera protocol for environments where wireless is unavailable or untrusted. One device
  displays a QR code, another scans it. Data can be chunked across multiple codes for larger payloads. See
  [qrt](https://github.com/smyrgeorge/qrt), a project that explores exactly this: encoding data into a sequence of QR
  codes, displaying them as a video on screen, and using a camera to capture and decode the frames.

No channel is mandatory. The protocol adapts to whatever is available.

## Privacy and trust

Every identity in Freepath is a cryptographic keypair — generated on your device, stored nowhere else. You are your key.
No account, no email, no phone number required.

Every piece of content is cryptographically signed. This doesn't mean you know the real-world identity of the author —
Freepath makes no such claim. What it does mean is that you can reliably tell whether two posts or messages were created
by the same person. The signature is a guarantee of consistency, not of identity. Forged or tampered content is
rejected.

Private messages are end-to-end encrypted, readable only by the intended recipient — even as they physically travel
through other people's devices.

Trust is local and personal. You decide who you trust. The network doesn't impose a global reputation system or a
shadowban mechanism. Your feed reflects your own web of trust, not an engagement algorithm.

## Content

Freepath can carry different types of content: short posts, long-form articles, links to external websites, images, or
even small videos.

However, there is an inherent constraint worth being honest about. Because everything is stored locally on each device —
with no cloud, no server, no external storage — space is genuinely limited. A phone is not a data center. Not everything
can travel with you, and not everything will.

For this reason, Freepath prioritizes **text**, and specifically **Markdown** — lightweight, expressive, and nearly
weightless in terms of storage. Photos are supported with care. Heavier content like video is technically possible but
will naturally be limited in practice, and storage policies on each device will reflect that reality.

This is not a bug. It's an honest consequence of the model — and in some ways, a feature. A network that fits in your
pocket can only carry what a pocket can hold.

### Deletion

If you delete something from your device, it's gone — locally. There is no server to restore it from, no backup in the
cloud, no admin who can retrieve it for you. The only way to get it back is if someone who still carries it crosses your
path and sends it to you again.

We find this concept deeply compelling. Deletion means something here. Content doesn't linger forever on a server
somewhere, out of your reach and out of your control. What you choose to remove, you remove. What survives, survives
because other people decided to keep it — not because a corporation did.

## Where we are

Freepath is currently an idea with a detailed technical design. No code yet. This repository exists to share the vision
and find out whether others think it's worth building.

If you've thought about these problems — decentralized communication, mesh networking, offline-first systems, digital
sovereignty — we'd love to hear from you.

## Get involved

This project is in its earliest stage. The best thing you can do right now is:

- **Share your thoughts** — open an issue, start a discussion
- **Spread the idea** — if this resonates with you, tell people
- **Contribute** — if you have experience in mobile development, cryptography, Bluetooth networking, or distributed
  systems, your perspective would be invaluable
