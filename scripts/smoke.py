"""Offline smoke of the installed launcher and SQLite on the actual host OS."""
import json
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parent.parent
launcher = root / "build/install/nai/bin" / ("nai.bat" if os.name == "nt" else "nai")
env = dict(os.environ)
env.pop("NAI_TOKEN", None)
env["NAI_CLI_SPEND_DISABLED"] = "1"
with tempfile.TemporaryDirectory(prefix="nai smoke 空格 ") as directory:
    config = str(Path(directory) / "isolated-config")
    env["XDG_CONFIG_HOME"] = config
    env["APPDATA"] = config
    for args in [
        ["schema"],
        ["models", "--json"],
        ["cost", "--prompt", "offline test", "--assume-tier", "opus", "--json"],
        ["history", "--json"],
    ]:
        command = [str(launcher), *args, "--data-dir", directory]
        result = subprocess.run(command, capture_output=True, env=env, timeout=45)
        assert result.returncode == 0, (args[0], result.returncode, result.stderr.decode("utf-8", errors="replace"))
        payload = json.loads(result.stdout.decode("utf-8"))
        assert payload.get("ok", True), args[0]
        if args[0] == "schema":
            assert {"login", "account"} <= {item["name"] for item in payload["commands"]}
        print(f"{args[0]}: OK")

    assert not Path(config).exists(), "Offline commands must not initialize grants"
