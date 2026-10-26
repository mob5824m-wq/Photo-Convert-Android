"""Push alert webhook dispatcher supporting ntfy.sh, Discord, Slack, and generic JSON webhooks."""

from __future__ import annotations

import json
import logging
import urllib.request
from typing import Tuple

logger = logging.getLogger("pixelnas.webhook")


def send_nas_webhook(webhook_url: str, title: str, message: str, event_type: str = "info") -> Tuple[bool, str]:
    """
    Sends a concise push alert to a configured webhook URL (Discord, Slack, ntfy.sh, or Home Assistant).
    Runs with a short timeout and never raises so NAS background loops stay uninterrupted.
    """
    clean_url = (webhook_url or "").strip()
    if not clean_url.startswith(("http://", "https://")):
        return False, "Webhook URL must start with http:// or https://"

    lower = clean_url.lower()
    full_text = f"[{title}] {message}"

    try:
        if "discord.com/api/webhooks" in lower or "discordapp.com/api/webhooks" in lower:
            body = json.dumps({"content": f"**{title}**\n{message}"}).encode("utf-8")
            headers = {"Content-Type": "application/json", "User-Agent": "PixelPhotoNAS/1.1"}
        elif "hooks.slack.com" in lower:
            body = json.dumps({"text": f"*{title}*\n{message}"}).encode("utf-8")
            headers = {"Content-Type": "application/json", "User-Agent": "PixelPhotoNAS/1.1"}
        elif "ntfy.sh/" in lower or "/ntfy/" in lower:
            body = message.encode("utf-8")
            headers = {
                "Title": title[:120],
                "Priority": "high" if event_type in ("quarantine", "thermal", "storage") else "default",
                "Tags": "camera,nas",
                "User-Agent": "PixelPhotoNAS/1.1",
            }
        else:
            body = json.dumps(
                {
                    "app": "Pixel Photo NAS",
                    "event": event_type,
                    "title": title,
                    "message": message,
                    "text": full_text,
                }
            ).encode("utf-8")
            headers = {"Content-Type": "application/json", "User-Agent": "PixelPhotoNAS/1.1"}

        req = urllib.request.Request(clean_url, data=body, headers=headers, method="POST")
        with urllib.request.urlopen(req, timeout=6.0) as resp:
            code = getattr(resp, "status", 200)
            return (200 <= code < 300), f"HTTP {code}"
    except Exception as exc:
        logger.warning("Webhook delivery failed (%s): %s", clean_url, exc)
        return False, str(exc)
