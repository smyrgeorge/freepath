# Freepath

An information network that lives in your pocket and spreads through human contact.

> [!NOTE]
> Freepath is at a very early stage. There is no code, no technical design, and no roadmap yet. We are currently in the
> process of thinking through how this could be built — collecting ideas, perspectives, and opinions. If something here
> resonates with you, we'd love to hear your thoughts.

🏠 [Homepage](https://smyrgeorge.github.io/freepath)

📱 [Wireframes](https://smyrgeorge.github.io/freepath/wireframes)

---

## Table of Contents

- [The idea](#the-idea)
    - [The name](#the-name)
    - [Why not a social network?](#why-not-a-social-network)
    - [The problem with information today](#the-problem-with-information-today)
    - [No internet required](#no-internet-required)
    - [It only needs two people](#it-only-needs-two-people)
- [You control what you share](#you-control-what-you-share)
    - [No one can collect everything](#no-one-can-collect-everything)
- [How information travels](#how-information-travels)
    - [Slowness as a filter](#slowness-as-a-filter)
    - [Local information ecosystems](#local-information-ecosystems)
    - [The role of strangers](#the-role-of-strangers)
    - [How devices talk to each other](#how-devices-talk-to-each-other)
    - [Hubs](#hubs)
        - [Governance and participation](#governance-and-participation)
- [Privacy and trust](#privacy-and-trust)
- [Content](#content)
    - [Deletion](#deletion)
- [Intelligence at the edge](#intelligence-at-the-edge)
    - [Curation and filtering](#curation-and-filtering)
    - [Smart storage management](#smart-storage-management)
    - [Translation and accessibility](#translation-and-accessibility)
    - [Spam and noise reduction](#spam-and-noise-reduction)
    - [Intelligent propagation](#intelligent-propagation)
    - [Writing assistance](#writing-assistance)
    - [What this is not](#what-this-is-not)
- [Where it could work](#where-it-could-work)
- [Concepts worth exploring](#concepts-worth-exploring)
    - [Messaging](#messaging)
    - [Presence-based voting](#presence-based-voting)
- [Where we are](#where-we-are)
- [Get involved](#get-involved)

## The idea

Freepath is an information network that requires no internet, no servers, and no central authority of any kind.

Instead of routing your messages through a data center, Freepath works the way stories and letters worked for thousands
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

### The problem with information today

We live in a world saturated with content. More is published every second than any person could read in a lifetime, and
the pace only accelerates. Yet for all this abundance, we seem to be getting worse at evaluating what we read — not
better. Algorithms decide what reaches us, optimizing for reaction rather than reflection. Volume has outpaced
understanding.

We believe part of the problem is structural. When a single platform mediates what billions of people see, the act of
filtering and evaluating information becomes centralized, opaque, and ultimately detached from the communities it
affects.

Freepath doesn't solve this problem outright, but it proposes a different structure. When information travels through
people — when the members of a network are themselves the medium — they naturally become more involved in what they
carry, what they pass on, and what they leave behind. Curation becomes human again. Evaluation happens at the edges, not
at the centre.

### No internet required

There is something we find genuinely compelling about a network with no internet backbone: information travels with
people. It moves because we move. It reaches places because we go there.

You don't receive anything passively from a server somewhere. You receive because you were present — because you went to
a place, crossed paths with someone, or chose to visit a [hub](#hubs). This is intentional. We believe access to
information should be tied to physical participation, not to a subscription
or an algorithm.

This constraint — that you have to go to the place — is not a limitation we're working around. It's a property we find
worth exploring. It keeps the network grounded in real communities, real spaces, and real encounters. And it makes
decentralization not just an architectural choice, but something you can feel.

### It only needs two people

There is no cold start problem here. The network doesn't need a critical mass of users to become useful. It begins the
moment two devices running Freepath are in the same place. That's it. Every conversation, every exchange, every
encounter is already the network working.

This also makes Freepath remarkably cost-effective. There are no servers to run, no infrastructure to maintain, no cloud
bills to pay. The entire network runs on hardware people already own. Getting started means downloading an application —
nothing more.

## You control what you share

When two phones meet, nothing is exchanged without your consent. You choose the strategy that fits your needs:

- **Starred** — only content you've explicitly marked to propagate
- **Last N posts** — a rolling window of your most recent activity
- **Everything** — your full local store, for those who want to be active carriers
- **Something else entirely** — the model is open, and other strategies are possible

This means propagation itself is decentralized. What spreads through the network is the result of thousands of
individual human decisions — not a private algorithm running on a server somewhere, optimizing for engagement or
burying content without explanation. There is no invisible hand. What travels, travels because people chose to carry it.

### No one can collect everything

Because content is fragmented across thousands of independent devices — each carrying a different subset, chosen by its
owner — it is practically impossible for any single actor to collect the full picture of what exists in the network.
There is no central repository to breach, no database to subpoena, no single point that holds it all.

This might look like a limitation. In some ways it is. But it also means the network has a natural resistance to
surveillance and control. To gather a significant portion of the content, someone would need access to a significant
portion of the people — physically. That is not something that scales easily for a malicious actor.

This fragmentation also quietly protects anonymity. Even if someone intercepts a piece of content, they see only a
fragment of a fragment. The full context — who wrote what, who carried it, who received it — is scattered across the
network in a way that cannot be easily reconstructed.

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

### Slowness as a filter

Freepath has no algorithm engineering virality. Content spreads only because people chose to carry it. This means the
network is naturally slow — and we think that's a good thing.

What survives and propagates is what people found worth passing on. There are no retweet storms, no engagement spikes,
no content that spreads faster than anyone can evaluate it. The pace of propagation is the pace of human movement. That
slowness is a quality filter no platform has ever managed to build deliberately.

### Local information ecosystems

Because propagation follows physical proximity, Freepath naturally forms geographic clusters. A neighbourhood, a
university campus, a city district each develops its own layer of information — local news, local knowledge, local
conversation — without needing a dedicated platform or a moderator to maintain it.

The network doesn't flatten everything into a global feed. It lets communities stay local by default, and connect to
the wider network only through the people who move between them.

### The role of strangers

You may carry content from people you've never met and will never know. Someone writes something in a city you've never
visited. It travels through a dozen hands before it reaches you, silently, as you walk past a stranger on a train. You
don't know the chain. You may not even know the author's name.

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

### Hubs

Beyond individual devices, Freepath introduces the concept of **hubs** — dedicated nodes that are always on and always
broadcasting. Unlike a regular phone that only shares content when someone happens to walk by, a hub is a fixed point
people can deliberately visit to collect information.

A hub is not a server in the traditional sense. It holds no special authority, stores no private data, and requires no
account to interact with. It is simply a persistent node, sitting quietly in a place, waiting for devices to come close.

Hubs can be placed anywhere — a cafeteria, a bookshop, a university library, a community centre, a public square. The
operator of a hub decides what content it carries and shares. You decide what you collect from it. You walk in, open the
app, and leave with whatever the hub had to offer.

The content a hub holds is controlled entirely by the owner of that place. It can be **public** — open for anyone who
comes within range to receive — or **encrypted**, accessible only to those who hold the right keys. A university might
broadcast open announcements to all students while keeping internal communications behind access control. A bookshop
might share its curated reading lists with anyone, or reserve them for members. The hub itself enforces nothing — the
content does.

This creates a natural layer of physical distribution points — informal, decentralized, and rooted in real places.

#### Governance and participation

The hub concept can be extended further. Rather than being controlled by a single owner, a hub could allow a group of
people to collectively manage its content — through some form of election, delegation, or voting mechanism. Who gets to
publish to a hub, what gets removed, how the hub evolves over time — these could all be decisions made by the community
that gathers around it, not imposed from above.

Hubs can also serve as physical voting points. A hub could pose a question to everyone who comes within range — an
opinion poll, a community decision, a local referendum — and silently collect responses as people pass through. No
central tallying server. No registration. The results exist in the network, carried and aggregated by the people
themselves.

This is still an open idea. The mechanisms are not defined yet — but the direction is deliberate: governance that is
local, participatory, and grounded in physical presence.

We find something worth sitting with in all of this. A network where strangers carry each other's words — not because
they were targeted, not because an algorithm surfaced it, but because a person decided it was worth bringing along.

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

We see this not as a bug but as an honest consequence of the model — and in some ways, a feature. A network that fits
in your pocket can only carry what a pocket can hold.

### Deletion

If you delete something from your device, it's gone — locally. There is no server to restore it from, no backup in the
cloud, no admin who can retrieve it for you. The only way to get it back is if someone who still carries it crosses your
path and sends it to you again.

We find this concept deeply compelling. Deletion means something here. Content doesn't linger forever on a server
somewhere, out of your reach and out of your control. What you choose to remove, you remove. What survives, survives
because other people decided to keep it — not because a corporation did.

## Intelligence at the edge

Modern AI models are shrinking. What once required a data center can now run entirely on a phone — no internet
connection,
no API key, no data leaving your device. This creates an interesting possibility for Freepath: intelligence that is
genuinely local, genuinely private, and genuinely yours.

We think AI belongs in Freepath the same way everything else does — at the edge, under your control, serving you rather
than serving someone's model training pipeline.

### Curation and filtering

The network doesn't impose a global ranking algorithm, and it never will. But that doesn't mean you have to read
everything. A local model running on your device can help you make sense of what arrives — summarizing long articles,
flagging content you've already seen in a different form, or surfacing posts that match your interests based on your
reading patterns. The difference is that this curation happens entirely on your device, with no signal sent outward and
no company learning from your choices.

### Smart storage management

Every device has limits. A local model can help decide what's worth keeping when space runs out — not by following a
platform's opaque policy, but by learning your own priorities over time. What do you tend to read? What do you skip?
What have you passed on? The model observes locally and acts locally. Nobody else sees any of this.

### Translation and accessibility

Freepath can carry content from communities that don't share your language. A local translation model means that a post
written in one language can be read in another without routing that text through a cloud translation service. The author
stays anonymous. The content stays private. The network stays decentralized.

### Spam and noise reduction

Without a central moderator, every device is responsible for its own signal quality. A local classifier — trained on
what you've marked as noise, not on what a platform decided was acceptable — can quietly filter out junk before it
reaches your reading queue. This is moderation that belongs to you, not to a trust-and-safety team in a building
somewhere.

### Intelligent propagation

Earlier, we described the propagation strategies available to each user — Starred, Last N posts, Everything, and
whatever else the model allows. These are intentional and human-driven. But AI can make them significantly smarter
without removing that human intent.

Rather than propagating a fixed window or a manually curated list, a local model can evaluate each piece of content
against parameters you define: topics you care about, authors you trust, geographic relevance, recency, estimated
quality, or even how many hops a post has already traveled. From this, it builds a dynamic selection — not a static
rule, but a live judgment about what is worth carrying right now.

Some examples of what this could look like in practice:

- **Topic-aware carrying** — you set interests (local politics, hiking trails, independent music) and the model
  prioritizes content that matches, letting unrelated content age out of your store first
- **Trust-weighted propagation** — content from authors you've engaged with before, or that has traveled through people
  you trust, is more likely to be carried forward
- **Freshness and decay** — the model can factor in how old a post is and how widely it has already spread,
  deprioritizing content that has likely reached saturation and making room for newer signals
- **Ethical load** — you can instruct the model to avoid carrying content matching certain patterns you find harmful,
  without requiring any external moderation infrastructure
- **Bandwidth sensitivity** — when a sync window is short (a brief encounter on a bus), the model selects only the
  highest-priority items; when time and proximity allow, it propagates more broadly

This turns propagation from a blunt instrument into something expressive. The network still carries what people choose
to carry — the principle doesn't change. But the choice can now be informed by a model that understands your
preferences,
applies them consistently, and does so entirely on your device.

The parameters are yours. The model is yours. What travels, travels because you decided it should — just with better
tools for making that decision.

### Writing assistance

Composing offline, without connectivity, doesn't mean composing without help. A small model on-device can assist with
drafting, editing, or translating your own posts before they go anywhere. Nothing leaves your phone until you choose to
share it.

### What this is not

Local AI in Freepath is not a recommendation engine optimizing for time-on-screen. It is not a classifier deciding what
the network is allowed to carry. It is not surveillance dressed up as assistance. There is no training feedback loop
feeding your behavior back to a model someone else controls.

The models run on your hardware. The outputs stay on your device. The decisions remain yours.

This is still an open area. The models available for on-device inference are improving rapidly, and what is practical
today will be far more capable in a short time. We think this is a direction worth building toward deliberately — not
as a feature added later, but as a design principle from the start.

## Where it could work

Freepath is not designed for one specific use case. Any place where people gather, move, and share — without reliable
internet or without wanting to depend on it — is a place where this concept could take root.

- **University campuses** — students sharing lecture notes, event announcements, and local news as they move between
  buildings and common spaces
- **Schools** — teachers and students exchanging materials, announcements, and resources without depending on a platform
  or internet connection
- **Public libraries** — a natural hub for a community's shared knowledge, open to anyone who walks through the door
- **Independent bookshops, cafes, and cultural spaces** — curated content, event listings, local zines, and
  recommendations carried by the people who pass through
- **Local neighbourhood networks** — residents sharing bulletins, alerts, recommendations, and community decisions
  within a few city blocks
- **Markets and fairs** — vendor listings, maps, schedules, and community notices spreading through the crowd
- **Music festivals and open-air events** — schedules, artist info, community boards, and announcements propagating
  organically through the crowd
- **Hiking trails and national parks** — trail conditions, safety alerts, and traveler notes passed between hikers
  moving in opposite directions
- **Sailing and maritime communities** — boats passing in harbours exchanging weather reports, navigation notes, and
  local knowledge
- **Underground and independent press** — journalism and writing that travels through communities without relying on
  platforms that can suppress or deprioritize it
- **Refugee camps and humanitarian zones** — information distribution in areas with no infrastructure, where access to
  communication is critical
- **Protest and activist movements** — organizing and sharing information in environments where connectivity is cut,
  monitored, or unreliable
- **Disaster response zones** — first responders and affected communities sharing real-time information when
  infrastructure has failed

This list is not exhaustive. Wherever people move, the network can follow.

## Concepts worth exploring

The ideas below are not part of the core design — they are extensions that feel natural given the foundation Freepath
builds on. Some overlap with earlier sections; they are collected here because they deserve their own space.

### Messaging

Freepath is described primarily as a broadcast medium — you write something, it travels, people receive it. But the same
infrastructure can carry private communication.

A messaging subsystem built on Freepath would work the same way: **store, carry, forward**. You send a message to
someone. Your phone holds it. The next time your device meets another Freepath device — a stranger on the street, a
friend at a café — that message is silently forwarded, carried one hop closer to its destination. Eventually, through a
chain of encounters you will never fully know, it arrives.

This is not instant messaging. It makes no such promise. A message might arrive in minutes, or hours, or tomorrow. The
delivery time depends entirely on the density and movement of the human mesh between sender and recipient.

But this constraint is not a failure — it is a different model of communication. One where presence matters. One where
the message arrives because people, physically, made it possible.

Privacy is preserved end-to-end. Messages are encrypted for the recipient's public key — readable only by them, even as
they pass through a dozen unknown devices. Intermediate carriers see nothing. The envelope is opaque. Only the
destination can open it.

This also changes how conversations feel. There is no read receipt, no typing indicator, no presence dot. You send, and
then you wait — not anxiously refreshing a screen, but knowing the message is on its way through the world, carried by
people going about their lives.

### Presence-based voting

Earlier, we touched on the idea of hubs collecting votes as people pass through. This deserves more careful development.

The principle is simple: **to vote, you have to go to the place**. Not log in, not submit a form, not click a button
from your sofa. You physically travel to a location — a hub, a public square, a community space — and your device
registers your participation. The act of being there is itself meaningful.

This is not just a technical mechanism. It is a statement about what voting should feel like. It reintroduces friction
as a feature. It anchors a decision to a place and a moment. It requires something of the participant beyond a tap on a
screen.

How it could work:

- A hub poses a question — a community proposal, a local decision, a neighbourhood referendum
- Any device that comes within range receives the ballot
- You review it and cast your response locally; your vote is signed with your cryptographic identity, so it cannot be
  duplicated or forged
- The hub collects responses silently as people arrive; other devices carry results outward as they leave
- Tallying is distributed — no single server counts the votes; the result emerges from the network as it propagates

The result is not instant. A vote cast at noon might not be fully aggregated until the end of the day, once enough
devices have passed through and carried the partial tallies outward. But it is verifiable, tamper-resistant, and
requires no central authority to run.

Participation is bounded by physical presence. You cannot vote for a community you have never visited. You cannot cast
ballots from a distance. The network enforces locality not through rules but through physics.

This is still an open direction. Questions of eligibility, double-voting prevention, anonymity, and result verification
are not yet resolved. But the direction feels right: governance that is local, physical, and grounded in the act of
showing up.

## Where we are

Freepath is currently an idea. This repository exists to share that idea and find out whether others think it's worth
building.

There is no technical design yet, and no code. The next steps are to start shaping the technical foundations of the
protocol and to begin building the application. Both are open — if you want to be part of that process, now is the time.

If you've thought about these problems — decentralized communication, mesh networking, offline-first systems, digital
sovereignty — we'd love to hear from you.

## Get involved

This project is in its earliest stage. The best thing you can do right now is:

- **Share your thoughts** — open an issue, start a discussion
- **Spread the idea** — if this resonates with you, tell people
- **Contribute** — if you have experience in mobile development, cryptography, Bluetooth networking, or distributed
  systems, your perspective would be invaluable
