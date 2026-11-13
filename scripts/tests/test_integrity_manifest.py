import shutil
import subprocess
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import integrity_manifest as im  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]


@pytest.fixture()
def repo(tmp_path):
    for rel in im.protected(ROOT) + ["integrity/protected-files.txt"]:
        dst = tmp_path / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / rel, dst)
    return tmp_path


def test_current_repo_constants_consistent():
    assert im.check_constants(ROOT) == []


def test_swapped_issuer_detected(repo):
    p = repo / "shared/src/commonMain/kotlin/cz/faltyn/elfall/shared/assets/DevAssets.kt"
    p.write_text(p.read_text().replace("GCUMZ6Z7", "GATTACK7", 1))
    assert any("issuer" in e for e in im.check_constants(repo))


def test_swapped_provider_url_detected(repo):
    p = repo / "shared/src/commonMain/kotlin/cz/faltyn/elfall/shared/offramp/StubProviders.kt"
    p.write_text(p.read_text().replace("https://sell.moonpay.com?", "https://sell.moonpay.evil?"))
    assert any("moonpay" in e for e in im.check_constants(repo))


def test_bank_account_pattern_rejected(repo):
    p = repo / "NOTICE"
    p.write_text(p.read_text() + "\nucet 1234567890/0100\n")
    assert any("bankovního účtu" in e for e in im.check_constants(repo))


def test_manifest_changes_when_file_changes(repo):
    before = im.build_manifest(repo)
    (repo / "AUTHORS").write_text("someone else\n")
    assert im.build_manifest(repo) != before


@pytest.mark.skipif(not shutil.which("ssh-keygen"), reason="ssh-keygen missing")
def test_signature_roundtrip(repo, tmp_path):
    key = tmp_path / "k"
    subprocess.run(["ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", str(key)], check=True)
    pub = (tmp_path / "k.pub").read_text().strip()
    (repo / "integrity/allowed_signers").write_text(f'ivosicek123 namespaces="elfall-integrity" {pub}\n')
    (repo / "integrity/MANIFEST.sha512").write_text(im.build_manifest(repo))
    subprocess.run(["ssh-keygen", "-Y", "sign", "-f", str(key), "-n", "elfall-integrity",
                    str(repo / "integrity/MANIFEST.sha512")], check=True, capture_output=True)
    assert im.verify_signature(repo) is None
    (repo / "integrity/MANIFEST.sha512").write_text("tampered\n")
    assert "neplatný" in im.verify_signature(repo)


def test_unsigned_reports_missing_key(repo):
    assert "allowed_signers" in im.verify_signature(repo)


def test_rsa_signer_rejected(repo):
    (repo / "integrity/allowed_signers").write_text('ivosicek123 namespaces="elfall-integrity" ssh-rsa AAAAB3Nza... x\n')
    assert "Ed25519" in im.verify_signature(repo)


def test_manifest_is_sha512(repo):
    line = im.build_manifest(repo).splitlines()[0]
    assert len(line.split()[0]) == 128
