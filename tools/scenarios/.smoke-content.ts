import { CONTENT_SCENARIOS } from "./scenarios-content";
console.log("count", CONTENT_SCENARIOS.length);
for (const s of CONTENT_SCENARIOS) console.log(s.id, s.key, s.name, "exclusive=" + !!s.exclusive, "timeout=" + (s.timeoutMs ?? "default"));
