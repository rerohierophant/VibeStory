"""Phase four HTTP acceptance. Real mode submits exactly ONE n=1 image task, with no automatic retry."""
import json
import os
import struct
import zlib


class PhaseFourAcceptance:
    def __init__(self, client, root, until, runtime, real):
        self.client, self.root, self.until, self.runtime, self.real = client, root, until, runtime, real

    def upload(self, client, label):
        def chunk(kind, data):
            return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)
        image = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 32, 32, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress((b"\x00" + bytes([32, 48, 70]) * 32) * 32)) + chunk(b"IEND", b"")
        boundary = "vibestory-phase4-boundary"
        body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"label\"\r\n\r\n{label}\r\n"
                f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"reference.png\"\r\nContent-Type: image/png\r\n\r\n").encode() + image + f"\r\n--{boundary}--\r\n".encode()
        return client.request("POST", f"/api/assets/scenes/{self.scene}/versions", body, 201, f"multipart/form-data; boundary={boundary}")

    def wait(self, task_id):
        def finished():
            task = self.client.request("GET", f"/api/tasks/{task_id}")
            assert task["state"] not in ("failed", "unknown"), {"taskId": task_id, "state": task["state"], "errorCode": task.get("errorCode")}
            return task if task["ready"] else None
        return self.until(finished, 150)

    def prepare(self):
        c = self.client
        self.project = c.request("POST", "/api/projects", {"name": "Phase 4 image and asset versions", "visualType": "realistic"}, 201)["id"]
        self.episode = c.request("POST", f"/api/projects/{self.project}/episodes", {}, 201)["id"]
        c.request("PUT", f"/api/episodes/{self.episode}/story", {"story": "雨夜街道，路灯在湿润的路面映出温暖的光。远处的便利店透出灯光，安静的空街上没有人物。\n\n天色渐亮，街道上的雨停了，路边的树叶轻轻晃动。"})
        breakdown = c.request("POST", f"/api/episodes/{self.episode}/breakdowns", {}, 202)["taskId"]
        self.wait(breakdown)
        shots = c.request("GET", f"/api/episodes/{self.episode}/shots")
        self.shot = shots[0]["id"]
        scene_name = shots[0]["sceneName"]
        scenes = c.request("GET", f"/api/projects/{self.project}/scenes")
        self.scene = next(s["id"] for s in scenes if s["name"] == scene_name)
        v1 = self.upload(c, "场景 v1")
        c.request("POST", f"/api/shots/{self.shot}/image-tasks", {}, 409)
        c.request("PUT", f"/api/shots/{self.shot}/status", {"status": "confirmed"})
        # This is the ONLY paid submission in real mode. No duplicate-submit or retry probe on a paid provider.
        self.task = c.request("POST", f"/api/shots/{self.shot}/image-tasks", {}, 202)["taskId"]
        if not self.real:
            assert c.request("POST", f"/api/shots/{self.shot}/image-tasks", {}, 202)["taskId"] == self.task
        v2 = self.upload(c, "场景 v2")
        completed = self.wait(self.task)
        shot = c.request("GET", f"/api/shots/{self.shot}")
        image = shot["images"][0]
        assert image["inputSnapshot"]["scene"]["versionId"] == v1["id"]
        assert shot["selectedImageId"] == image["id"] and "stale" not in image
        self.image_url = image["url"]
        payload = c.request("GET", self.image_url)
        assert payload.startswith(b"\x89PNG") or payload.startswith(b"\xff\xd8")
        output = self.runtime / ("phase4-real-image" if self.real else "phase4-mock-image")
        output = output.with_suffix(".png" if payload.startswith(b"\x89PNG") else ".jpg")
        output.write_bytes(payload)
        assert completed["attempts"] == 1
        if not self.real:
            regenerate = c.request("POST", f"/api/shots/{self.shot}/image-tasks", {"regenerateNonce": "explicit-next-version"}, 202)["taskId"]
            assert regenerate != self.task
            self.wait(regenerate)
            shot = c.request("GET", f"/api/shots/{self.shot}")
            assert shot["images"][1]["inputSnapshot"]["scene"]["versionId"] == v2["id"]
            assert shot["selectedImageId"] == image["id"]
            c.request("PUT", f"/api/shots/{self.shot}/selected-image", {"imageId": shot["images"][1]["id"]}, 204)
            asset_task = c.request("POST", f"/api/assets/scenes/{self.scene}/image-tasks", {}, 202)["taskId"]
            self.wait(asset_task)
        self.expected_shots = c.request("GET", f"/api/episodes/{self.episode}/shots?taskActive=true")
        self.expected_assets = c.request("GET", f"/api/projects/{self.project}/scenes")
        assert self.expected_assets[0]["usage"]["shots"] > 0
        summary = {"mode": "real" if self.real else "mock", "projectId": self.project, "episodeId": self.episode,
                   "taskId": self.task, "attempts": completed["attempts"], "imageCount": 1 if self.real else 2,
                   "requestedModel": os.environ.get("IMAGE_MODEL", "image-01") if self.real else "mock-image-v1",
                   "sourceVersionId": v1["id"], "currentVersionId": v2["id"], "guidance": image.get("guidance"),
                   "imagePath": str(output), "bytes": len(payload), "realReferenceInputVerified": False}
        (self.runtime / "phase4-summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
        print(f"PASS: Phase 4 {'ONE real text-to-image request' if self.real else 'Mock generation/regeneration'} -> private image; immutable source version; asset versions and selection", flush=True)

    def check_restart(self, client):
        assert client.request("GET", f"/api/episodes/{self.episode}/shots?taskActive=true") == self.expected_shots
        assert client.request("GET", f"/api/projects/{self.project}/scenes") == self.expected_assets
        assert client.request("GET", self.image_url)

    def check_scope(self, client, other):
        other.request("GET", self.image_url, expected=404)
        other.request("GET", f"/api/tasks/{self.task}", expected=404)
        other.request("POST", f"/api/assets/scenes/{self.scene}/image-tasks", {}, 404)
        other.request("POST", f"/api/shots/{self.shot}/image-tasks", {}, 404)
        other.request("GET", self.expected_assets[0]["versions"][0]["url"], expected=404)
        print("PASS: Phase 4 images, snapshots and asset selections survive both JVM restarts; cross-user image/version/task operations are 404", flush=True)
