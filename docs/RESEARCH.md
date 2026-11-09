# Why Foyer: the problem research

Research done on 2026-10-15. The brief: a real, important, technical problem that people can adopt as a plugin or service
for free; this time **not** an AI product; ideas from community discussion (Reddit was the suggested source).

**A note on sources.** reddit.com could not be read from this environment (blocked for both the fetch tool and the browser
automation), so the problem was chosen from news coverage of incidents that dominated Indian and US social media, official
statements, and vendor pages. Sources are listed at the end.

## The problem: flash crowds are unfair, and they crash sites

Whenever something scarce goes on sale at a fixed time (concert tickets, Tatkal train tickets, admission forms, limited
product drops, vaccine slots), three things happen together:

1. **The site falls over.** Everyone arrives in the same minute.
   - Coldplay's Mumbai shows on BookMyShow (Sept 2024): about **13 million** people tried to buy; the site went down for
     about **9.5 minutes**, some people lost their place in the queue, and fans saw "six lakh people ahead of you".
   - Maharashtra Class 11 (FYJC) admissions, May 2025: the portal crashed within an hour of going live with about **1.5 million**
     students registering at once; the schedule had to be redone.
   - Ticketmaster, Taylor Swift's Eras Tour presale (Nov 2022): **3.5 billion** requests, four times its previous record;
     a US Senate Judiciary hearing followed.
2. **Bots take the stock.** BookMyShow's CEO said roughly **10 million** of the Coldplay "instances" were largely bots and that
   the company was "underprepared for the bots". The Railways minister said over half of IRCTC booking attempts were bots;
   tens of millions of suspicious accounts were deactivated, and Aadhaar OTP became mandatory for Tatkal from July 2025, a
   move critics (SFLC.in) said doesn't fix the real issue: the portal crashing at peak hours. In 2021, Telegram groups sold
   CoWIN vaccine slots booked by scripts that got past the CAPTCHA.
3. **People can't tell whether it was fair.** Fans alleged foul play when tickets reappeared on resale sites within minutes.
   A queue that the user can't verify is a queue nobody trusts.

### Why it's hard

- **Fairness at T-0.** A first-come-first-served queue rewards whoever fires requests in the first millisecond, which is
  exactly what bots are good at. Fairness needs a different rule for people who arrived early.
- **Bots without punishing humans.** CAPTCHAs are solved by paid farms and drive away real users, especially on phones and
  in Indian languages. Identity requirements (Aadhaar OTP) raise privacy objections and add load.
- **Scale with honesty.** Millions of people poll "where am I?". Each answer must be cheap, consistent across many servers,
  and true. A crash of the queue itself is the worst outcome of all.
- **It has to drop into existing sites.** Ticketing, railways and universities run very different stacks. The protection has
  to be a plugin, a gateway or a few lines of config, not a rewrite.
- **It's expensive today.** Queue-it, the market leader, is quote-only enterprise software. Queue-Fair's free tier allows one
  waiting room for 100 customers. No credible open-source, self-hosted option turned up.

## What Foyer does about it

| Problem | Foyer |
|---|---|
| Site crashes under the surge | Visitors wait in Foyer, not on the origin. The origin only ever sees the admission rate it chose (for example 300 people a minute, at most 2,000 browsing at once). |
| Bots win at T-0 | Everyone who arrives before opening joins a **lobby**; at opening, positions are assigned by a **lottery**. Arriving at T-30 minutes or T-0.001 s gives the same chance. |
| "Was it rigged?" | The lottery is **commit-reveal**: the hash of the random seed is published before the sale; the seed is revealed at opening; anyone can recompute every position from their lobby number. |
| Bots hammer joins | Joining needs a **proof-of-work** solved in the browser (no puzzles for humans, about a second of CPU), with difficulty raised for networks that join suspiciously often, and rate limits per IP and subnet. |
| Passes are resold or replayed | Admission passes are short-lived, signed (Ed25519), bound to the visitor and room, and single-session; the origin reports purchases so passes can't be reused. |
| Origins run anything | Verify passes offline in Java (a servlet filter / Spring Boot starter) with Foyer's public key, online through nginx `auth_request`, or with a one-line JS tag for soft protection. |
| Operators fly blind | A live console: queue length, arrivals, admissions, wait times, bots blocked, controls to pause or change the rate mid-sale, and a fairness report afterwards. |

## Sources

- BookMyShow CEO on Coldplay bots: Business Today, 29 Jan 2025 — https://businesstoday.in/india/story/we-were-underprepared-for-the-bots-bookmyshow-ceo-on-coldplay-ticketing-row-462362-2025-01-29
- Coldplay sale and queue: Outlook Money — https://www.outlookmoney.com/news/coldplay-bookmyshow-ticket-fiasco-why-are-fans-calling-out-a-foul-play ; Pollstar — https://news.pollstar.com/?p=1155351
- IRCTC bots and Aadhaar mandate: NewsBytes — https://www.newsbytesapp.com/news/india/ashwini-vaishnaw-says-over-half-irctc-booking-attempts-were-bots/tldr ; Storyboard18 (SFLC criticism) — https://www.storyboard18.com/digital/railways-aadhaar-mandate-for-tatkal-bookings-sparks-legal-privacy-backlash-71087.htm ; Free Press Journal — https://www.freepressjournal.in/tech/aadhaar-otp-boosts-tatkal-fairness-irctc-blocks-302-cr-fake-ids-availability-jumps-65
- Maharashtra FYJC portal crash 2025: Careers360 — https://news.careers360.com/maharashtra-fyjc-admission-2025-registration-mahafyjcadmissions-in-portal-crash-error-dead-helpline-numbers-worry-11th-aspirants
- Ticketmaster / Eras Tour, Senate hearing: Pollstar — https://news.pollstar.com/2022/11/30/senators-push-ftc-to-fight-bots-after-taylor-swift-fiasco/ ; Scripps News — https://www.scrippsnews.com/us-news/senators-press-ticketmaster-to-step-up-defenses-against-bots
- CoWIN slot bots: The Quint — https://thequint.com/cyber/bypassing-captcha-how-tech-savvy-indians-are-fooling-cowin
- Queue-it / alternatives pricing: Capterra — https://www.capterra.com/p/232255/Queue-IT/ ; G2 Queue-Fair — https://www.g2.com/products/queue-fair/pricing

Figures are as reported by those outlets and companies; bot counts in particular are company claims.
