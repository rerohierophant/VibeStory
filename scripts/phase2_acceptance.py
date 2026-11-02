"""Additional real-HTTP checks invoked by verify-phase1.py --phase2."""
import binascii
import json
import struct
import urllib.parse
import uuid
import zlib


def png():
    def chunk(kind, content):
        return struct.pack(">I", len(content)) + kind + content + struct.pack(">I", binascii.crc32(kind + content) & 0xffffffff)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 2, 1, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(b"\0\x12\x34\x56\x65\x43\x21")) + chunk(b"IEND", b""))


def multipart(label, image):
    boundary = "vibestory-" + uuid.uuid4().hex
    data = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"label\"\r\n\r\n{label}\r\n"
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"image\"; filename=\"reference.png\"\r\n"
            "Content-Type: image/png\r\n\r\n").encode()
    return data + image + f"\r\n--{boundary}--\r\n".encode(), f"multipart/form-data; boundary={boundary}"


class PhaseTwoAcceptance:
    def __init__(self, client, sql, root, user_id, until):
        self.client, self.sql, self.root, self.user_id, self.until = client, sql, root, user_id, until

    def prepare(self):
        image, content_type = multipart("HTTP 自定义参考图", png())
        self.style = self.client.request("POST", "/api/visual-styles", image, 201, content_type=content_type)
        oversized, content_type = multipart("超限", bytes(5 * 1024 * 1024 + 1))
        self.client.request("POST", "/api/visual-styles", oversized, 413, content_type=content_type)
        self.project = self.client.request("POST", "/api/projects",
            {"name": "Phase 2 persistent story", "visualType": self.style["id"], "style": "雨夜光影"}, 201)
        self.project_id = int(self.project["id"])
        self.episodes = []
        for index in range(1, 4):
            episode = self.client.request("POST", f"/api/projects/{self.project_id}/episodes", {}, 201)
            assert episode["index"] == index
            story = f"  第 {index} 集\r\n雨夜重逢。🙂\n\n保留空格与换行  "
            episode = self.client.request("PATCH", f"/api/episodes/{episode['id']}", {"title": f"HTTP 第 {index} 集", "summary": f"梗概 {index}"})
            episode = self.client.request("PUT", f"/api/episodes/{episode['id']}/story", {"story": story})
            self.episodes.append(episode)
        updated = self.client.request("PATCH", f"/api/projects/{self.project_id}", {"name": "HTTP 重命名项目"})
        assert updated["name"] == "HTTP 重命名项目"
        search = self.client.request("GET", "/api/projects?q=" + urllib.parse.quote("HTTP 重命名"))
        assert [project["id"] for project in search] == [str(self.project_id)]
        first_episode = int(self.episodes[0]["id"])
        shot = int(self.sql(f"insert into shot(user_id, project_id, episode_id, seq, status, description) values ({self.user_id}, {self.project_id}, {first_episode}, 1, 'CONFIRMED', 'HTTP fixture') returning id"))
        self.sql(f"insert into shot_image(shot_id, episode_id, selected, storage_key) values ({shot}, {first_episode}, true, 'mock/phase2-frame')")
        task = self.client.request("POST", f"/api/shots/{shot}/video-tasks", {"regenerateNonce": str(uuid.uuid4())}, 202)
        self.task_id = int(task["taskId"])
        self.until(lambda: self.client.request("GET", f"/api/tasks/{self.task_id}")["ready"])
        self.check_restart(self.client)
        print("PASS: Phase 2 custom image upload (including real 413), scoped project search, three independently saved stories", flush=True)

    def check_restart(self, client):
        project = client.request("GET", f"/api/projects/{self.project_id}")
        assert project["name"] == "HTTP 重命名项目"
        assert project["visualType"] == self.style["id"]
        assert project["visualPreset"]["label"] == self.style["label"]
        assert len(project["episodes"]) == 3
        for expected, actual in zip(self.episodes, project["episodes"]):
            assert actual["id"] == expected["id"] and actual["index"] == expected["index"]
            assert actual["title"] == expected["title"] and actual["summary"] == expected["summary"]
            assert actual["story"] == expected["story"]
        assert client.request("GET", self.style["image"]).startswith(b"\x89PNG")
        stats = client.request("GET", f"/api/projects/{self.project_id}/overview")
        assert stats == {"episodeCount": 3, "shotCount": 1, "confirmedCount": 1, "imageCount": 1, "videoCount": 1}, stats

    def check_scope_and_cleanup(self, client, other):
        episode_id = int(self.episodes[0]["id"])
        other.request("GET", self.style["image"], expected=404)
        other.request("GET", f"/api/visual-styles/{self.style['id']}", expected=404)
        other.request("POST", "/api/projects", {"name": "bad", "visualType": self.style["id"]}, 404)
        other.request("GET", f"/api/episodes/{episode_id}", expected=404)
        other.request("PUT", f"/api/episodes/{episode_id}/story", {"story": "bad"}, 404)
        other.request("PATCH", f"/api/projects/{self.project_id}", {"name": "bad"}, 404)
        other.request("DELETE", f"/api/projects/{self.project_id}", expected=404)
        client.request("DELETE", f"/api/visual-styles/{self.style['id']}", expected=204)
        assert all(style["id"] != self.style["id"] for style in client.request("GET", "/api/visual-styles"))
        assert client.request("GET", self.style["image"]).startswith(b"\x89PNG")
        self.check_restart(client)
        client.request("DELETE", f"/api/episodes/{int(self.episodes[1]['id'])}", expected=204)
        fourth = client.request("POST", f"/api/projects/{self.project_id}/episodes", {}, 201)
        assert fourth["index"] == 4
        assert [episode["index"] for episode in client.request("GET", f"/api/projects/{self.project_id}/episodes")] == [1, 3, 4]
        image_key = self.sql(f"select storage_key from custom_style where id='{self.style['id']}'")
        video_key = self.sql(f"select storage_key from video where task_id={self.task_id}")
        client.request("DELETE", f"/api/projects/{self.project_id}", expected=204)
        client.request("GET", f"/api/projects/{self.project_id}", expected=404)
        client.request("GET", f"/api/tasks/{self.task_id}", expected=404)
        client.request("GET", self.style["image"], expected=404)
        self.until(lambda: not (self.root / "data/storage" / image_key).exists() and not (self.root / "data/storage" / video_key).exists())
        assert self.sql(f"select count(*) from episode where project_id={self.project_id}") == "0"
        assert self.sql(f"select count(*) from task where project_id={self.project_id}") == "0"
        print("PASS: Phase 2 survives both JVM restarts; cross-user story/style access is 404; middle deletion keeps indexes; archived style and project media are cleaned", flush=True)
