import pytest

import src.main as daemon_main


@pytest.fixture(autouse=True)
def use_example_config(monkeypatch):
    """Run every test against config.example.yaml, never a local config.yaml.

    The app's startup calls load_config() with no path, which prefers
    packages/tonal-daemon/config.yaml. That file is gitignored and is what a
    developer or a device install edits (for example a lower weight cap), so
    without this the TestClient tests depend on whoever runs them.
    Tests that pass an explicit path to load_config() still load that file.
    """
    real_load_config = daemon_main.load_config
    example = str(daemon_main.BASE_DIR / "config.example.yaml")

    def load_config(path=None):
        return real_load_config(path or example)

    monkeypatch.setattr(daemon_main, "load_config", load_config)
