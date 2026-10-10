#!/usr/bin/env python3
"""HTTP acceptance smoke for a running FocusDuo server; Python standard library only."""

import argparse
import json
import secrets
import sys
import time
import uuid
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


class SmokeFailure(Exception):
    """Safe diagnostic text; never includes HTTP payloads or credentials."""


class NoRedirects(HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        # Never forward an Authorization header to a redirect destination.
        return None


def check(condition, message):
    # Do not use assert: python -O must not disable acceptance checks.
    if not condition:
        raise SmokeFailure(message)


def server_origin(value):
    try:
        parsed = urlsplit(value)
        valid = (
            parsed.scheme in ("http", "https")
            and parsed.hostname
            and parsed.username is None
            and parsed.password is None
            and parsed.path in ("", "/")
            and not parsed.query
            and not parsed.fragment
        )
        parsed.port  # Validate malformed/out-of-range ports as well.
    except ValueError:
        valid = False
    if not valid:
        raise argparse.ArgumentTypeError(
            "Use an HTTP(S) server origin without credentials, /api/v1, query or fragment"
        )
    return value.rstrip("/")


def wait_duration(value):
    try:
        seconds = float(value)
    except ValueError:
        seconds = -1
    if not 0 <= seconds <= 3600:
        raise argparse.ArgumentTypeError("Wait seconds must be between 0 and 3600")
    return seconds


class Smoke:
    def __init__(self, base_url):
        self.base_url = base_url
        self.http = build_opener(NoRedirects())
        self.active_tokens = []

    def request(self, method, path, token=None, body=None, key=None, revision=None,
                expected=200, timeout=10):
        headers = {"Accept": "application/json"}
        if token is not None:
            headers["Authorization"] = "Bearer " + token
        if key is not None:
            headers["Idempotency-Key"] = key
        if revision is not None:
            headers["X-Room-Revision"] = str(revision)
        data = None
        if body is not None:
            headers["Content-Type"] = "application/json"
            data = json.dumps(body, separators=(",", ":")).encode("utf-8")
        request = Request(self.base_url + "/api/v1" + path, data, headers, method=method)
        try:
            response = self.http.open(request, timeout=timeout)
        except HTTPError as error:
            response = error
        except (URLError, OSError):
            raise SmokeFailure("HTTP transport failed during " + method + " " + path) from None
        with response:
            status = response.status
            payload = response.read()
        check(status == expected,
              method + " " + path + ": expected HTTP " + str(expected) + ", got " + str(status))
        if status == 204:
            check(not payload, "Logout response must have no body")
            return None
        try:
            decoded = json.loads(payload)
        except (ValueError, UnicodeError):
            raise SmokeFailure(method + " " + path + ": response is not valid JSON") from None
        check(isinstance(decoded, dict), method + " " + path + ": expected a JSON object")
        return decoded

    def wait_until_ready(self, seconds):
        deadline = time.monotonic() + seconds
        while True:
            try:
                result = self.request("GET", "/me", expected=401, timeout=2)
                check(result.get("code") == "UNAUTHORIZED", "Unexpected readiness response")
                print("PASS readiness: unauthenticated /api/v1/me returns 401", flush=True)
                return
            except SmokeFailure:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise SmokeFailure("Server did not become ready within the requested wait") from None
                time.sleep(min(0.5, remaining))

    def register(self, label):
        result = self.request("POST", "/auth/register", body={
            "username": "smoke_" + label + "_" + uuid.uuid4().hex[:16],
            "displayName": "Smoke " + label.upper(),
            "password": secrets.token_urlsafe(24),
        }, expected=201)
        token = result.get("accessToken")
        check(isinstance(token, str) and bool(token), "Registration did not return an access token")
        self.active_tokens.append(token)
        user = result.get("user", {})
        check(bool(user.get("id")), "Registration did not return a user id")
        check(bool(result.get("expiresAt")), "Registration did not return session expiry")
        check(self.request("GET", "/me", token) == user, "GET /me differs from registered user")
        return token, user["id"]

    def command(self, token, method, path, room=None, body=None):
        return self.request(method, path, token, body, str(uuid.uuid4()),
                            None if room is None else room["revision"])

    @staticmethod
    def participant(snapshot, user_id):
        for participant in snapshot["participants"]:
            if participant["userId"] == user_id:
                return participant
        raise SmokeFailure("Expected participant missing from snapshot")

    def run(self):
        owner, owner_id = self.register("a")
        peer, peer_id = self.register("b")
        print("PASS registration: two independent users and bearer sessions", flush=True)

        create_key = str(uuid.uuid4())
        settings = {"focusDurationSeconds": 300, "breakDurationSeconds": 60}
        created = self.request("POST", "/rooms", owner, settings, create_key)
        room = created
        base = "/rooms/" + room["roomId"]
        check(room["revision"] == 1 and room["round"] is None, "Unexpected initial room state")
        check(room["ownerId"] == owner_id and len(room["participants"]) == 1,
              "Initial room must contain only its owner")
        room = self.command(peer, "POST", "/rooms/join", body={"code": " " + room["code"].lower() + " "})
        check(len(room["participants"]) == 2, "Partner did not occupy the second seat")
        check(self.request("GET", "/rooms/current", peer)["room"]["roomId"] == room["roomId"],
              "Current-room recovery returned a different room")

        replay = self.request("POST", "/rooms", owner, settings, create_key)
        check(replay == created, "Replayed create must return the original saved snapshot")
        check(self.request("GET", base, owner)["revision"] == room["revision"],
              "Replayed create changed current room revision")
        reused = self.request("POST", "/rooms", owner,
                              {"focusDurationSeconds": 600, "breakDurationSeconds": 60},
                              create_key, expected=409)
        check(reused.get("code") == "IDEMPOTENCY_KEY_REUSED", "Changed replay body was not rejected")
        print("PASS room: create/join/recovery and exact idempotency replay", flush=True)

        room = self.command(owner, "POST", base + "/rounds", room, {"kind": "FOCUS"})
        round_id = room["round"]["id"]
        round_path = base + "/rounds/" + round_id
        started_at = room["round"]["startedAt"]
        check(room["round"]["status"] == "RUNNING", "Focus did not start")

        room = self.command(peer, "POST", base + "/tasks", room, {"title": "  Smoke task  "})
        task = self.participant(room, peer_id)["tasks"][0]
        check(task["title"] == "Smoke task" and task["completed"] is False,
              "Created task was not trimmed or initially incomplete")
        task_path = base + "/tasks/" + task["id"]
        task_key = str(uuid.uuid4())
        task_revision = room["revision"]
        task_body = {"completed": True}
        completed_task = self.request("PATCH", task_path, peer, task_body, task_key, task_revision)
        room = self.command(peer, "PATCH", base + "/me", completed_task, {"goal": "Smoke goal"})
        replay = self.request("PATCH", task_path, peer, task_body, task_key, task_revision)
        check(replay == completed_task, "Task replay with original revision must return saved response")
        check(self.request("GET", base, peer)["revision"] == room["revision"],
              "Task replay rolled back or modified the room")

        room = self.command(owner, "POST", round_path + "/pause", room)
        paused = room["round"]
        check(paused["status"] == "PAUSED" and paused["endsAt"] is None, "Round did not pause")
        check(self.request("GET", base, peer)["round"] == paused, "Paused timer did not remain frozen")
        room = self.command(owner, "POST", round_path + "/resume", room)
        check(room["round"]["status"] == "RUNNING" and room["round"]["endsAt"] is not None,
              "Round did not resume")
        check(room["round"]["startedAt"] == started_at, "Resume changed startedAt")
        room = self.command(owner, "POST", round_path + "/finish", room)
        finished = room["round"]
        check(finished["status"] == "COMPLETED" and finished["completionReason"] == "MANUAL",
              "Early finish did not produce COMPLETED/MANUAL")
        check(finished["remainingMs"] == 0 and finished["endsAt"] is None and finished["endedAt"],
              "Terminal round fields are invalid")
        check(0 <= finished["activeElapsedMs"] < finished["durationSeconds"] * 1000,
              "Early finish must preserve actual active time")
        print("PASS focus: peer task edit, replay, pause/resume and manual finish", flush=True)

        history_path = "/history/" + round_id
        history = self.request("GET", history_path, owner)
        check(self.request("GET", history_path, peer) == history, "Partners received different history")
        frozen_peer = self.participant(history, peer_id)
        check(frozen_peer["goal"] == "Smoke goal" and frozen_peer["totalTaskCount"] == 1
              and frozen_peer["completedTaskCount"] == 1, "History task/goal snapshot is incorrect")
        check(self.request("GET", "/history", owner)["items"] == [history],
              "Focus history should contain exactly one result")
        room = self.command(peer, "PATCH", task_path, room, {"title": "Changed later", "completed": False})
        room = self.command(peer, "PATCH", base + "/me", room, {"goal": "Changed goal"})
        check(self.request("GET", history_path, peer) == history, "Later edits changed immutable history")
        print("PASS history: single shared result stays immutable after task/goal edits", flush=True)

        room = self.command(owner, "POST", base + "/rounds", room, {"kind": "BREAK"})
        break_id = room["round"]["id"]
        check(room["round"]["kind"] == "BREAK" and room["round"]["status"] == "RUNNING",
              "Break did not start after completed focus")
        room = self.command(peer, "POST", base + "/close", room)
        check(room["status"] == "CLOSED" and room["round"]["status"] == "CANCELLED"
              and room["round"]["completionReason"] == "ROOM_CLOSED", "Close did not cancel active break")
        for token in (owner, peer):
            check(self.request("GET", "/rooms/current", token)["room"] is None,
                  "Closing room did not release active membership")
            check(self.request("GET", base, token)["status"] == "CLOSED",
                  "Historical member could not read closed room")
            items = self.request("GET", "/history", token)["items"]
            check(len(items) == 2 and {item["roundId"] for item in items} == {round_id, break_id},
                  "Expected exactly one focus result and one break result")
        check(self.request("GET", history_path, owner) == history, "Closing room rewrote focus history")
        cancelled = self.request("GET", "/history/" + break_id, peer)
        check(cancelled["status"] == "CANCELLED" and cancelled["completionReason"] == "ROOM_CLOSED",
              "Cancelled break history has incorrect status")
        print("PASS break/close: peer closes room, memberships released, both results retained", flush=True)

        for token in (owner, peer):
            self.request("POST", "/auth/logout", token, expected=204)
            self.active_tokens.remove(token)
            revoked = self.request("GET", "/me", token, expected=401)
            check(revoked.get("code") == "UNAUTHORIZED", "Logged-out token remained authorized")
        print("PASS logout: both revoked tokens return 401", flush=True)

    def cleanup(self):
        """Only close rooms belonging to the accounts created by this process."""
        for token in list(self.active_tokens):
            try:
                room = self.request("GET", "/rooms/current", token, timeout=3)["room"]
                if room is not None and room["status"] == "OPEN":
                    self.command(token, "POST", "/rooms/" + room["roomId"] + "/close", room)
            except Exception:
                print("WARN cleanup: could not confirm closure of this smoke account's room", file=sys.stderr)
            finally:
                try:
                    self.request("POST", "/auth/logout", token, expected=204, timeout=3)
                except Exception:
                    print("WARN cleanup: could not revoke one smoke session", file=sys.stderr)
                self.active_tokens.remove(token)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8090", type=server_origin,
                        help="Running server origin, without /api/v1 (default: %(default)s)")
    parser.add_argument("--wait-seconds", default=60.0, type=wait_duration,
                        help="Maximum readiness wait, 0-3600 seconds (default: %(default)s)")
    args = parser.parse_args()
    smoke = Smoke(args.base_url)
    try:
        smoke.wait_until_ready(args.wait_seconds)
        smoke.run()
    except SmokeFailure as failure:
        print("FAIL " + str(failure), file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("FAIL interrupted", file=sys.stderr)
        return 130
    except Exception as failure:
        # Raw exception text or payloads could contain credentials. Report only the type.
        print("FAIL unexpected " + type(failure).__name__, file=sys.stderr)
        return 1
    finally:
        smoke.cleanup()
    print("PASS FocusDuo packaged-server HTTP smoke", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
