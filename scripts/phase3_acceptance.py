"""Real HTTP phase-three checks. --real-text makes one synthetic-story request to the configured adapter."""
import json
import os


class PhaseThreeAcceptance:
    def __init__(self, client, sql, root, user_id, until, runtime, real):
        self.client, self.sql, self.root, self.user_id, self.until, self.runtime, self.real = client, sql, root, user_id, until, runtime, real

    def wait(self, client, task_id):
        def finished():
            task = client.request("GET", f"/api/tasks/{task_id}")
            assert task["state"] not in ("failed", "unknown"), {"taskId": task_id, "state": task["state"], "errorCode": task["errorCode"]}
            return task if task["ready"] else None
        return self.until(finished, 150)

    def prepare(self):
        client = self.client
        project = client.request("POST", "/api/projects", {"name": "Phase 3 story breakdown", "visualType": "realistic"}, 201)
        self.project = int(project["id"])
        episode = client.request("POST", f"/api/projects/{self.project}/episodes", {}, 201)
        self.episode = int(episode["id"])
        paragraphs = [
            "沈夜撑着黑伞站在雨夜街道，路灯把积水照得发亮。他在便利店门口停下，手里攥着一封旧信。",
            "许星遥在便利店货架旁整理牛奶。听到自动门的铃声，她回头看见沈夜，两个人都没有立刻说话。",
            "店员周叔端起一只纸碗，笑着问：「还是老样子？」沈夜点头，收起伞，雨水在门边形成一小滩。",
            "许星遥把牛奶放回货架，走到柜台前。她说：「我以为你不会回来了。」沈夜看着纸碗里升起的热气。",
            "沈夜从口袋里拿出旧信，放在柜台上。许星遥没有打开，只是轻轻抚平信封边角，指尖停在熟悉的字迹上。",
            "周叔把关东煮推到两人之间，转身去整理货架。便利店里只剩冷柜的嗡鸣，玻璃窗上有一串串雨水。",
            "许星遥抬头问：「这一次待多久？」沈夜低声说：「等雨停。」她望向窗外，嘴角却慢慢有了笑意。",
            "沈夜把一份萝卜递给许星遥，她接过竹签。他们并排坐在窗边，旧信放在两人中间，谁都没有急着拆开。",
            "周叔关掉柜台上一盏刺眼的灯，换成柔和的暖光。他在门口挂出暂停营业的牌子，又把牌子翻了回去。",
            "街道上的车灯从玻璃上掠过，照亮两人短暂的沉默。许星遥终于打开旧信，第一行字让她停住了呼吸。",
            "沈夜低下头，不敢看她的眼睛。许星遥读完信，把信纸重新折好，说：「明天陪我去一趟旧宅吧。」",
            "沈夜抬头答应。门外的雨渐渐小了，周叔把黑伞移到门边，许星遥拿起另一把伞，示意沈夜一起走。",
            "两人走出便利店，沿着雨夜街道慢慢前行。许星遥把旧信收进包里，沈夜走在靠车道的一侧，替她挡住溅起的水。",
            "拐角处的路灯亮起，他们的影子拉得很长。许星遥问沈夜明早几点见，他笑着回答：「你开门的时候。」",
            "便利店里，周叔从玻璃窗后看着他们离开，抬手关掉门口的灯。雨水落在空荡的街道，脚步声逐渐远去。",
            "旧宅客厅的沙发上，许星遥放下那封旧信。她整理好茶几上的单据，看向院子里的桂花，轻轻拉开了窗帘。",
            "院子的桂花被雨水打落，许星遥拿来扫帚，把石阶清理干净。她在门口留了一盏灯，又把第二只茶杯摆到桌上。",
            "天快亮时，沈夜再次走过便利店。周叔隔着玻璃向他点头，沈夜把伞靠在门边，提着热牛奶朝旧宅走去。",
        ]
        self.story = "\n\n".join(paragraphs)
        assert len(self.story) >= 800
        client.request("PUT", f"/api/episodes/{self.episode}/story", {"story": self.story})
        first = client.request("POST", f"/api/episodes/{self.episode}/breakdowns", {}, 202)
        self.task = int(first["taskId"])
        completed = self.wait(client, self.task)
        shots = client.request("GET", f"/api/episodes/{self.episode}/shots?taskActive=true")
        assert len(shots) >= 10
        assert all(shot["status"] == "draft" for shot in shots)
        original_ids = [shot["id"] for shot in shots]
        self.first = int(shots[0]["id"])
        client.request("POST", f"/api/shots/{self.first}/video-tasks", {"regenerateNonce": "draft-is-blocked"}, 409)
        client.request("PUT", f"/api/shots/{self.first}/status", {"status": "confirmed"})
        client.request("PATCH", f"/api/shots/{self.first}", {"description": "人工复核：角色在便利店门口停步", "durationMs": 4500})
        client.request("POST", f"/api/episodes/{self.episode}/breakdowns", {}, 409)
        assert [shot["id"] for shot in client.request("GET", f"/api/episodes/{self.episode}/shots")] == original_ids
        if not self.real:
            # Full replacement is deterministic and free on Mock. The real run deliberately makes one model call.
            next_task = client.request("POST", f"/api/episodes/{self.episode}/breakdowns", {"replaceExisting": True}, 202)
            self.task = int(next_task["taskId"])
            self.wait(client, self.task)
            shots = client.request("GET", f"/api/episodes/{self.episode}/shots")
            assert not set(original_ids) & {shot["id"] for shot in shots}
            assert all(shot["status"] == "draft" for shot in shots)
            self.first = int(shots[0]["id"])
            client.request("PUT", f"/api/shots/{self.first}/status", {"status": "confirmed"})
            client.request("PATCH", f"/api/shots/{self.first}", {"description": "人工复核：角色在便利店门口停步", "durationMs": 4500})
        client.request("POST", f"/api/episodes/{self.episode}/shots/confirm-all", {})
        client.request("PUT", f"/api/shots/{self.first}/status", {"status": "draft"})
        characters = client.request("GET", f"/api/projects/{self.project}/characters")
        scenes = client.request("GET", f"/api/projects/{self.project}/scenes")
        assert len({card["name"].strip().lower() for card in characters}) == len(characters)
        assert len({card["name"].strip().lower() for card in scenes}) == len(scenes)
        if self.real:
            assert completed["attempts"] == 1
            diagnostic = completed["history"][0]["diagnosticUrl"]
            raw = client.request("GET", diagnostic)["rawResponse"]
            assert os.environ.get("TEXT_API_KEY", "") not in raw
            self.diagnostic = diagnostic
        else:
            self.diagnostic = None
        self.expected_shots = client.request("GET", f"/api/episodes/{self.episode}/shots")
        summary = {"mode": "real" if self.real else "mock", "projectId": self.project, "episodeId": self.episode,
                   "taskId": self.task, "storyCharacters": len(self.story), "shotCount": len(self.expected_shots),
                   "characterCards": len(characters), "sceneCards": len(scenes), "requestedModel": os.environ.get("TEXT_MODEL") if self.real else "mock-splitter-v1",
                   "actualModel": completed["history"][0]["model"], "attempts": completed["attempts"]}
        (self.runtime / "phase3-summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
        print(f"PASS: Phase 3 {'real model' if self.real else 'Mock'} task -> {len(self.expected_shots)} shots, scoped asset names, edit/confirm/unconfirm, replacement guard", flush=True)

    def check_restart(self, client):
        episode = client.request("GET", f"/api/episodes/{self.episode}")
        assert episode["story"] == self.story and episode["breakdown"] == "done"
        assert client.request("GET", f"/api/episodes/{self.episode}/shots") == self.expected_shots
        assert client.request("GET", f"/api/episodes/{self.episode}/breakdowns/latest")["id"] == str(self.task)

    def check_scope(self, client, other):
        for path in (f"/api/episodes/{self.episode}/shots", f"/api/episodes/{self.episode}/breakdowns/latest",
                     f"/api/projects/{self.project}/characters", f"/api/projects/{self.project}/scenes", f"/api/shots/{self.first}"):
            other.request("GET", path, expected=404)
        other.request("POST", f"/api/episodes/{self.episode}/breakdowns", {"replaceExisting": True}, 404)
        other.request("PATCH", f"/api/shots/{self.first}", {"description": "bad"}, 404)
        other.request("PUT", f"/api/shots/{self.first}/status", {"status": "confirmed"}, 404)
        if self.diagnostic:
            other.request("GET", self.diagnostic, expected=404)
        print("PASS: Phase 3 shot edits and state survive both JVM restarts; cross-user shots/assets/breakdowns/diagnostics are 404", flush=True)
