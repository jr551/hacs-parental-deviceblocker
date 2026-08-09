"""Child-scoped points portal helpers.

This module deliberately has no Home Assistant imports so its filtering rules can
be unit tested without a running Home Assistant instance.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Iterable, Mapping


@dataclass(frozen=True)
class ChildIdentity:
    """The one compatible chore-manager child assigned to a blocker device."""

    child_id: str
    name: str


def resolve_child_identity(
    windows_username: str,
    device_name: str,
    point_entities: Iterable[Mapping[str, Any]],
) -> ChildIdentity | None:
    """Resolve a Windows account to exactly one child without accepting input from the PC."""

    username = _key(windows_username)
    expected = username
    device = _key(device_name)
    matches: list[tuple[int, ChildIdentity]] = []

    for entity in point_entities:
        attributes = entity.get("attributes", {})
        child_id = str(attributes.get("child_id", ""))
        name = str(attributes.get("child_name", "")).strip()
        name_key = _key(name)
        if not child_id or not name_key:
            continue
        score = 0
        if name_key == expected:
            score = 100
        elif name_key == username:
            score = 90
        elif len(username) >= 4 and (
            name_key.startswith(username) or username.startswith(name_key)
        ):
            score = 80
        elif name_key and name_key in device:
            score = 60
        if score:
            matches.append((score, ChildIdentity(child_id=child_id, name=name)))

    if not matches:
        return None
    best_score = max(score for score, _identity in matches)
    best = {identity for score, identity in matches if score == best_score}
    return next(iter(best)) if len(best) == 1 else None


def build_portal_snapshot(
    child: ChildIdentity,
    entities: Mapping[str, Mapping[str, Any]],
    buttons: Iterable[Mapping[str, Any]],
) -> dict[str, Any]:
    """Return a minimal snapshot containing data for one child only."""

    points_entity = entities.get("points", {})
    points_attributes = points_entity.get("attributes", {})
    points = _number(points_entity.get("state", points_attributes.get("points", 0)))
    unit = str(points_attributes.get("unit_of_measurement", "Stars"))[:32]

    stats_attributes = entities.get("stats", {}).get("attributes", {})
    chores_attributes = entities.get("chores", {}).get("attributes", {})
    availability = entities.get("availability", {}).get("attributes", {}).get(
        "chore_availability", {}
    )
    buttons = list(buttons)

    completed_ids = {
        str(item.get("chore_id", ""))
        for item in chores_attributes.get("todays_completions", [])
        if str(item.get("child_id", "")) == child.child_id
    }
    chores: list[dict[str, Any]] = []
    for item in stats_attributes.get("assigned_chores", []):
        chore_id = str(item.get("id", ""))
        if not chore_id:
            continue
        available_for_child = bool(availability.get(chore_id, {}).get(child.child_id, True))
        action_available = _button_available(buttons, child.child_id, "chore_id", chore_id)
        chores.append(
            {
                "id": chore_id,
                "name": str(item.get("name", "Chore"))[:120],
                "points": _number(item.get("points", 0)),
                "time_category": str(item.get("time_category", "anytime"))[:32],
                "completed_today": chore_id in completed_ids,
                "can_complete": available_for_child
                and chore_id not in completed_ids
                and action_available,
            }
        )

    rewards: list[dict[str, Any]] = []
    time_offers: list[dict[str, Any]] = []
    rewards_attributes = entities.get("rewards", {}).get("attributes", {})
    for item in rewards_attributes.get("rewards", []):
        reward_id = str(item.get("id", ""))
        assigned_to = [str(value) for value in item.get("assigned_to", [])]
        if not reward_id or (assigned_to and child.child_id not in assigned_to):
            continue
        if item.get("is_sold_out") or item.get("is_expired") or not item.get("is_available", True):
            continue
        calculated_costs = item.get("calculated_costs", {})
        cost = _number(calculated_costs.get(child.child_id, item.get("cost", 0)))
        action_available = _button_available(buttons, child.child_id, "reward_id", reward_id)
        entry = {
            "id": reward_id,
            "name": str(item.get("name", "Reward"))[:120],
            "description": str(item.get("description", ""))[:300],
            "cost": cost,
            "quantity": item.get("quantity"),
            "can_claim": points >= cost and action_available,
        }
        rewards.append(entry)
        if _is_time_offer(entry["name"]):
            time_offers.append(entry)

    activity_attributes = entities.get("activity", {}).get("attributes", {})
    activity: list[dict[str, Any]] = []
    for item in activity_attributes.get("recent_transactions", []):
        if str(item.get("child_id", "")) != child.child_id:
            continue
        activity.append(
            {
                "kind": "transaction",
                "points": _number(item.get("points", 0)),
                "reason": str(item.get("reason", "Points updated"))[:180],
                "created_at": str(item.get("created_at", ""))[:64],
            }
        )
    for item in activity_attributes.get("recent_completions", []):
        if str(item.get("child_id", "")) != child.child_id:
            continue
        activity.append(
            {
                "kind": "completion",
                "points": _number(item.get("points", 0)),
                "reason": str(item.get("chore_name", item.get("name", "Chore completed")))[:180],
                "created_at": str(item.get("completed_at", item.get("created_at", "")))[:64],
            }
        )
    activity.sort(key=lambda item: item["created_at"], reverse=True)

    return {
        "child": {"name": child.name, "points": points, "unit": unit},
        "stats": {
            "total_earned": _number(points_attributes.get("total_points_earned", 0)),
            "chores_completed": _number(points_attributes.get("total_chores_completed", 0)),
            "current_streak": _number(points_attributes.get("current_streak", 0)),
        },
        "chores": chores,
        "rewards": rewards,
        "time_offers": time_offers,
        "activity": activity[:20],
    }


def _is_time_offer(name: str) -> bool:
    """Screen-time rewards get a prominent buy banner on the lock screen."""
    key = name.casefold()
    return "pc time" in key or "screen time" in key or "more time" in key


def _button_available(
    buttons: Iterable[Mapping[str, Any]], child_id: str, item_key: str, item_id: str
) -> bool:
    return any(
        str(button.get("state", "")) != "unavailable"
        and str(button.get("attributes", {}).get("child_id", "")) == child_id
        and str(button.get("attributes", {}).get(item_key, "")) == item_id
        for button in buttons
    )


def _key(value: str) -> str:
    return "".join(character for character in value.casefold() if character.isalnum())


def _number(value: Any) -> int | float:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return 0
    return int(number) if number.is_integer() else number


PORTAL_HTML = r"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>Points portal</title>
  <style>
    :root { color-scheme: dark; font-family: "Segoe UI", system-ui, sans-serif; }
    * { box-sizing: border-box; }
    body { margin: 0; min-height: 100vh; color: #f7f9fc; background: radial-gradient(circle at top right,#244b78,#101722 48%); }
    main { width: min(1080px,100%); margin: auto; padding: 26px; }
    header { display:flex; align-items:center; justify-content:space-between; gap:20px; margin-bottom:20px; }
    h1,h2,p { margin:0; } h1 { font-size:clamp(28px,4vw,44px); } h2 { font-size:21px; margin-bottom:14px; }
    .points { min-width:160px; padding:16px 22px; border-radius:18px; text-align:center; background:#ffd659; color:#352600; box-shadow:0 10px 30px #0005; }
    .points strong { display:block; font-size:38px; line-height:1; } .points span { font-weight:700; }
    .stats,.columns { display:grid; grid-template-columns:repeat(3,1fr); gap:14px; }
    .columns { grid-template-columns:1fr 1fr; margin-top:14px; }
    section,.stat { border:1px solid #ffffff18; background:#172231e8; border-radius:18px; padding:18px; box-shadow:0 10px 30px #0003; }
    .stat { text-align:center; } .stat strong { display:block; font-size:27px; color:#8dc8ff; }
    .item { display:grid; grid-template-columns:1fr auto; align-items:center; gap:12px; padding:13px 0; border-top:1px solid #ffffff14; }
    .item:first-of-type { border-top:0; } .muted { color:#a9b7c8; font-size:13px; margin-top:4px; }
    button { border:0; border-radius:10px; padding:10px 14px; color:white; background:#347ed4; font-weight:700; cursor:pointer; }
    button:disabled { opacity:.45; cursor:not-allowed; } button.reward { background:#8a58d5; }
    .activity { margin-top:14px; } .activity-row { display:grid; grid-template-columns:auto 1fr auto; gap:12px; padding:10px 0; border-top:1px solid #ffffff14; align-items:center; }
    .delta { min-width:54px; font-weight:800; color:#7de0a5; } .delta.minus { color:#ff8e8e; }
    .empty,.error { color:#a9b7c8; padding:16px 0; } .error { color:#ffaaa5; }
    #notice { min-height:24px; margin:10px 0; color:#99d1ff; font-weight:600; }
    #timeoffers { display:grid; gap:12px; margin:0 0 16px; }
    .offer { display:flex; align-items:center; justify-content:space-between; gap:16px; padding:18px 22px;
             border-radius:18px; border:1px solid #35d07f55; background:linear-gradient(120deg,#123524e8,#0f2a3ce8);
             box-shadow:0 10px 30px #0004; }
    .offer h3 { margin:0; font-size:22px; } .offer .muted { margin-top:2px; }
    .offer button { background:#35d07f; color:#04240f; font-size:18px; padding:14px 22px; border-radius:14px;
                    white-space:nowrap; box-shadow:0 6px 18px #35d07f44; }
    .offer button:disabled { background:#35d07f; }
    .offer .need { color:#ffd659; font-weight:700; font-size:13px; margin-top:4px; }
    #parent { margin-top:14px; border-color:#ffffff26; }
    #parent .row { display:flex; gap:10px; align-items:center; flex-wrap:wrap; margin-top:10px; }
    #parent input { flex:0 0 130px; padding:12px 14px; border-radius:12px; border:1px solid #ffffff2e;
                    background:#0d1622; color:#f7f9fc; font-size:20px; letter-spacing:6px; text-align:center; }
    #parent button { background:#e0a03a; color:#2b1c00; }
    #parentNotice { margin-top:8px; font-weight:600; min-height:20px; color:#ffd659; }
    @media(max-width:760px){ .columns,.stats{grid-template-columns:1fr} header{align-items:flex-start}.points{min-width:130px} }
  </style>
</head>
<body>
<main>
  <header><div><div class="muted">PARENTAL DEVICE BLOCKER</div><h1 id="title">Points portal</h1><p id="subtitle" class="muted">Loading your account…</p></div><div class="points"><strong id="points">—</strong><span id="unit">Points</span></div></header>
  <div id="notice"></div>
  <div id="timeoffers"></div>
  <div class="stats"><div class="stat"><strong id="earned">—</strong>Total earned</div><div class="stat"><strong id="completed">—</strong>Chores completed</div><div class="stat"><strong id="streak">—</strong>Current streak</div></div>
  <div class="columns"><section><h2>Available chores</h2><div id="chores"></div></section><section><h2>Points shop</h2><div id="rewards"></div></section></div>
  <section class="activity"><h2>Your recent activity</h2><div id="activity"></div></section>
  <section id="parent">
    <h2>Parent override</h2>
    <div class="muted">A parent can enter the PIN to give 1 more hour — no stars are spent. Two tries only.</div>
    <div class="row">
      <input id="parentPin" type="password" inputmode="numeric" autocomplete="off" placeholder="PIN" maxlength="12">
      <button id="parentButton" type="button">Give 1 more hour</button>
    </div>
    <div id="parentNotice"></div>
  </section>
</main>
<script>
(() => {
  "use strict";
  const key = new URLSearchParams(location.search).get("key") || "";
  const root = location.pathname.replace(/\/portal$/, "");
  let busy = false;
  const text = (value) => String(value ?? "");
  const node = (tag, className, value) => { const element=document.createElement(tag); if(className) element.className=className; if(value!==undefined) element.textContent=text(value); return element; };
  async function api(path, options={}) {
    const response = await fetch(root + path, {...options, cache:"no-store", headers:{"X-Device-Blocker-Key":key,"Content-Type":"application/json",...(options.headers||{})}});
    const payload = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(payload.error || "Home Assistant rejected the request");
    return payload;
  }
  function empty(container, message) { container.replaceChildren(node("div","empty",message)); }
  function actionButton(label, className, enabled, handler) { const button=node("button",className,label); button.disabled=!enabled; button.addEventListener("click",handler); return button; }
  async function perform(type, id, label) {
    if (busy || !confirm(label + "?")) return;
    busy = true;
    document.querySelectorAll("button").forEach(button => button.disabled = true);
    const notice=document.getElementById("notice"); notice.textContent="Sending request…";
    try { await api("/portal/action",{method:"POST",body:JSON.stringify({type,id})}); notice.textContent="Done — Home Assistant is updating your points."; }
    catch(error) { notice.textContent=error.message; }
    finally { busy=false; await refresh(); }
  }
  async function performOffer(item) {
    if (busy || !confirm("Buy “"+item.name+"” for "+item.cost+"?")) return;
    busy = true;
    document.querySelectorAll("button").forEach(button => button.disabled = true);
    const notice=document.getElementById("notice"); notice.textContent="Buying more time…";
    try {
      await api("/portal/action",{method:"POST",body:JSON.stringify({type:"claim_reward",id:item.id})});
      notice.textContent="✅ Purchased! Your PC will unlock automatically in under a minute — hang tight.";
    }
    catch(error) { notice.textContent=error.message; }
    finally { busy=false; setTimeout(refresh, 4000); }
  }
  function render(data) {
    document.getElementById("title").textContent=data.child.name + "’s points";
    document.getElementById("subtitle").textContent="This PC is signed into your child portal";
    document.getElementById("points").textContent=text(data.child.points);
    document.getElementById("unit").textContent=text(data.child.unit);
    document.getElementById("earned").textContent=text(data.stats.total_earned);
    document.getElementById("completed").textContent=text(data.stats.chores_completed);
    document.getElementById("streak").textContent=text(data.stats.current_streak);
    const offers=document.getElementById("timeoffers"); offers.replaceChildren();
    (data.time_offers||[]).forEach(item => {
      const card=node("div","offer");
      const copy=node("div");
      copy.append(node("h3","","🕘 "+item.name),node("div","muted",item.description||""));
      if(!item.can_claim) copy.append(node("div","need","You need "+item.cost+" "+text(data.child.unit)+" — you have "+text(data.child.points)));
      const buy=actionButton("Buy for "+item.cost+" ⭐","offerbuy",item.can_claim,()=>performOffer(item));
      card.append(copy,buy);
      offers.append(card);
    });
    const chores=document.getElementById("chores"); chores.replaceChildren();
    data.chores.forEach(item => { const row=node("div","item"); const copy=node("div"); copy.append(node("strong","",item.name),node("div","muted",item.points+" points · "+item.time_category)); const label=item.completed_today?"Done today":"Mark complete"; row.append(copy,actionButton(label,"",item.can_complete,()=>perform("complete_chore",item.id,"Mark “"+item.name+"” complete"))); chores.append(row); });
    if(!data.chores.length) empty(chores,"No chores are available right now.");
    const rewards=document.getElementById("rewards"); rewards.replaceChildren();
    data.rewards.forEach(item => { const row=node("div","item"); const copy=node("div"); copy.append(node("strong","",item.name),node("div","muted",item.cost+" points"+(item.description?" · "+item.description:""))); row.append(copy,actionButton("Claim","reward",item.can_claim,()=>perform("claim_reward",item.id,"Claim “"+item.name+"” for "+item.cost+" points"))); rewards.append(row); });
    if(!data.rewards.length) empty(rewards,"The shop has no available items.");
    const activity=document.getElementById("activity"); activity.replaceChildren();
    data.activity.forEach(item => { const row=node("div","activity-row"); const delta=node("div","delta"+(Number(item.points)<0?" minus":""),(Number(item.points)>0?"+":"")+item.points); const when=item.created_at?new Date(item.created_at).toLocaleString():""; row.append(delta,node("div","",item.reason),node("div","muted",when)); activity.append(row); });
    if(!data.activity.length) empty(activity,"No recent activity yet.");
  }
  async function refresh() { try { render(await api("/portal/data")); } catch(error) { document.getElementById("notice").textContent=error.message; } }
  async function parentOverride() {
    const field=document.getElementById("parentPin");
    const notice=document.getElementById("parentNotice");
    const pin=field.value.trim();
    if(!pin){ notice.textContent="Enter the parent PIN."; return; }
    const button=document.getElementById("parentButton");
    button.disabled=true; notice.textContent="Checking PIN…";
    try {
      // The server owns verification, attempt counting, and lockout.
      const result=await api("/parent_override",{method:"POST",body:JSON.stringify({pin})});
      field.value="";
      notice.textContent="✅ Granted "+(result.minutes||60)+" more minutes — unlocking now.";
      setTimeout(refresh, 4000);
    } catch(error) { notice.textContent=error.message; }
    finally { button.disabled=false; }
  }
  document.getElementById("parentButton").addEventListener("click", parentOverride);
  document.getElementById("parentPin").addEventListener("keydown", (event) => { if(event.key==="Enter") parentOverride(); });
  refresh(); setInterval(refresh, 10000);
})();
</script>
</body>
</html>"""
