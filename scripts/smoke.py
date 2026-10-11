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


def diagnose_windows_path(directory):
    """Keep failed launcher/path checks inspectable without touching credentials."""
    if os.name != "nt":
        return
    source = r'''
import java.nio.file.*;
import java.nio.charset.Charset;
import java.sql.DriverManager;
import java.util.Arrays;
class PathProbe {
    static void probe(String label, String value) {
        System.out.println(label + " codepoints=" + Arrays.toString(value.codePoints().toArray()));
        Path path = Path.of(value);
        System.out.println(label + " exists=" + Files.isDirectory(path));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path.resolve("probe.db"))) {
            System.out.println(label + " sqlite=OK");
        } catch (Exception failure) { failure.printStackTrace(); }
    }
    public static void main(String[] args) {
        System.out.println("charset=" + Charset.defaultCharset() + " native=" + System.getProperty("native.encoding"));
        probe("env", System.getenv("NAI_SMOKE_DATA_DIR"));
        probe("argv", args[0]);
    }
}
'''
    with tempfile.TemporaryDirectory(prefix="nai-path-probe-") as probe_dir:
        path = Path(probe_dir) / "PathProbe.java"
        path.write_text(source, encoding="ascii")
        probe_env = dict(env, NAI_SMOKE_DATA_DIR=directory)
        java = str(Path(env["JAVA_HOME"]) / "bin/java.exe") if "JAVA_HOME" in env else "java"
        result = subprocess.run([java, "--enable-native-access=ALL-UNNAMED", "--class-path",
                                 str(root / "build/install/nai/lib/*"), str(path), directory],
                                env=probe_env, capture_output=True, timeout=45)
        print("Windows path diagnostic:", result.returncode,
              result.stdout.decode("utf-8", errors="replace"),
              result.stderr.decode("utf-8", errors="replace"), flush=True)


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
        if result.returncode != 0:
            diagnose_windows_path(directory)
        assert result.returncode == 0, (args[0], result.returncode,
                                       result.stdout.decode("utf-8", errors="replace"),
                                       result.stderr.decode("utf-8", errors="replace"))
        payload = json.loads(result.stdout.decode("utf-8"))
        assert payload.get("ok", True), args[0]
        if args[0] == "schema":
            assert {"login", "account", "prompt"} <= {item["name"] for item in payload["commands"]}
        if args[0] == "history":
            assert payload["count"] == 0 and payload["images"] == []
        print(f"{args[0]}: OK")

    def prompt(args, stdin=None, expected=0):
        result = subprocess.run(
            [str(launcher), "prompt", *args, "--json", "--data-dir", directory],
            input=stdin, capture_output=True, env=env, timeout=45,
        )
        assert result.returncode == expected, (args, result.returncode,
                                              result.stdout.decode("utf-8", errors="replace"),
                                              result.stderr.decode("utf-8", errors="replace"))
        payload = json.loads(result.stdout.decode("utf-8"))
        assert payload["ok"] == (expected == 0), payload
        if expected:
            assert payload["error"]["code"] == expected, payload
        else:
            assert payload["offline"] is True
        return payload

    positive = Path(directory) / "正向 prompt.txt"
    negative = Path(directory) / "负向 prompt.txt"
    macros = Path(directory) / "宏 macros.json"
    positive.write_bytes("\ufeff猫, !macro:scene!\r\nblue eyes\r\n".encode("utf-8"))
    negative.write_text("!macro:bad!", encoding="utf-8")
    macros.write_text(json.dumps({"scene": "<garden|forest>", "bad": "blurry", "hero": "1.2::hero::"}), encoding="utf-8")
    args = ["--prompt-file", str(positive), "--negative-file", str(negative),
            "--macros-file", str(macros), "--append", "night", "--append", "stars",
            "--negative-append", "bad hands", "--prompt-random-seed", "42", "--seed", "99",
            "--character", "!macro:hero!@left!!!macro:bad!", "--quality-tags", "none", "--uc-preset", "3"]
    first = prompt(args)
    assert first == prompt(args), "Explicit template/image seeds must reproduce the payload"
    assert "\r" not in first["resolved"]["prompt"] and "\ufeff" not in first["resolved"]["prompt"]
    assert first["resolved"]["prompt"].endswith("blue eyes, night, stars")
    assert first["resolved"]["negativePrompt"] == "blurry, bad hands"
    assert first["weights"][0]["target"] == "characters[0].prompt"
    assert first["weightValidation"] == "not-performed"
    assert first["payload"]["parameters"]["seed"] == 99
    print("prompt files/macros/characters/reproducibility: OK")

    piped = prompt(["--prompt", "-", "--quality-tags", "none"], "猫\r\nsmile".encode("utf-8"))
    assert piped["resolved"]["prompt"] == "猫\nsmile"
    negative_stdin = prompt(["--prompt", "cat", "--negative-file", "-"], b"blurry")
    assert negative_stdin["resolved"]["negativePrompt"] == "blurry"
    print("prompt stdin: OK")

    text = prompt(["--prompt", 'poster "TITLE"', "--model", "v5-full",
                   "--character", 'hero "RIGHT"@right', "--character", 'hero "LEFT"@left',
                   "--quality-tags", "none", "--uc-preset", "3"])
    assert text["payload"]["input"].endswith("teXt: TITLE\n\nLEFT\n\nRIGHT")
    parameters = text["payload"]["parameters"]
    assert parameters["v4_prompt"]["caption"]["base_caption"] == text["payload"]["input"]
    assert parameters["params_version"] == 4 and parameters["ucPresetId"] == "none"
    preserved = prompt(["--prompt", "||<red|blue>|green|| |other caption", "--quality-tags", "none"])
    assert preserved["resolved"]["prompt"] == "||<red|blue>|green|| |other caption"
    malformed_weight = "NaN::cat::, 1.2.3::hair::, (dog:1.2), {unfinished"
    unchecked = prompt(["--prompt", malformed_weight, "--quality-tags", "none", "--uc-preset", "3"])
    assert unchecked["payload"]["input"] == malformed_weight
    assert unchecked["weightValidation"] == "not-performed"
    print("prompt model payload/text/official randomizer: OK")

    malformed = Path(directory) / "invalid-utf8.txt"
    malformed.write_bytes(b"\xc3(")
    cycle = Path(directory) / "cycle.json"
    cycle.write_text('{"a":"!macro:a!"}', encoding="utf-8")
    for args in [
        ["--prompt", "!macro:missing!"],
        ["--prompt", "!macro:a!", "--macros-file", str(cycle)],
        ["--prompt-file", str(Path(directory) / "missing.txt")],
        ["--prompt-file", str(malformed)],
        ["--prompt", "cat", "--prompt-file", str(positive)],
        ["--prompt", "-", "--negative-file", "-"],
        ["--prompt", "cat", "--unknown", "value"],
    ]:
        prompt(args, expected=2)
    # Parse failures occur before the command reporter is constructed.
    result = subprocess.run([str(launcher), "prompt", "--json", "--prompt"],
                            capture_output=True, env=env, timeout=45)
    assert result.returncode == 2 and json.loads(result.stdout.decode("utf-8"))["ok"] is False
    print("prompt structured usage errors: OK")

    assert not Path(config).exists(), "Offline commands must not initialize grants"
