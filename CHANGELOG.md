## 2026.39.1 — 2026-09-24

### 🐛 Fixes
- strip set-name codes until none are left (`8e816f5`)
- reload the scope placeholder with /wardrobe reload (`ca9f2b2`)
- settle wardrobe clicks at the highest listener priority (`389859e`)
- store wardrobe set names as plain text (`402fba2`)
- log wardrobe menu config warnings once per reload (`9fc87fe`)
- report the wardrobe scope mode to bStats (`1a3852a`)
- keep the rename sign off block entities and blocks already in use (`56112f8`)
- accept wardrobe pieces by equippable slot, one at a time (`4aff790`)
- commit each wardrobe swap in one transaction (`a0ac482`)
- refuse per-profile wardrobes when no profile resolves (`41a5868`)
- close wardrobes on reload and keep sets past a shrunk menu visible (`5adba0f`)
- keep one live wardrobe per player so menus can't go stale (`29a3159`)
- refuse to open a wardrobe that failed to load (`9e2dfc7`)
- stop re-setting the cursor a tick after a wardrobe click (`2eaf32e`)

### ♻️ Refactors
- look wardrobe sounds up in the sound registry (`d59cc8e`)

### 📝 Documentation
- cover damaged slots, set names, profile scoping and commands (`0631231`)
- add MIT license (`92c4f8e`)

## 2026.39.0 — 2026-09-23

### 🔧 Other
- paper-api 26.2.build.121-stable -> 26.2.build.123-stable (`c4cf494`)

## 2026.37.0 — 2026-09-13

### 🐛 Fixes
- release at 10:00 Central or later, not exactly 10:00 (`d367ba6`)

## 2026.36.0 — 2026-09-06

### ✨ Features
- set names and GUI-free equipping (`3949c63`)

### 🐛 Fixes
- serialize wardrobe loads behind queued saves (`ab71d87`)

### 📝 Documentation
- state the Paper 26.2-or-newer requirement (`8c3acb3`)

## 2026.32.0 — 2026-08-07

### ✨ Features
- report anonymous usage stats via bStats (`0e924f7`)
- place wardrobe nav by row/column and split out messages.yml (`885d6d6`)
- permission-based wardrobe slot limits (`c807c1a`)

### 🐛 Fixes
- make wardrobe writes ordered, snapshotted, drained and loud (`ec6e5fa`)

### 📝 Documentation
- add a README and clear out NoHunger leftovers (`6688354`)

## 2026.29.0 — 2026-07-18

### ✨ Features
- authentic Hypixel column wardrobe (dyes, active-lock, paging, dates) (`59d707a`)
- RoyalWardrobe — Hypixel-style armor wardrobe (`19c8baf`)

### 🐛 Fixes
- allow own-inventory use so pieces can be placed; add shift-deposit (`41e4dd7`)

### ♻️ Refactors
- move to the suite's EcoMenus dialect + MySQL default (`a110519`)

