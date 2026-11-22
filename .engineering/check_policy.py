"""Check the installed policy snapshot; this does not certify product quality."""
import hashlib
import json
from pathlib import Path


def verify(root):
    root=Path(root)
    lock=json.loads((root/'.engineering/policy-lock.json').read_text())
    policy=root/'.engineering/POLICY.md'
    if policy.is_symlink() or hashlib.sha256(policy.read_bytes()).hexdigest()!=lock['sha256']:
        raise ValueError('Pinned Engineering Excellence policy drift')
    for name in ('AGENTS.md','CLAUDE.md','GEMINI.md'):
        path=root/name
        if path.is_symlink(): raise ValueError('Instruction symlink refused')
        text=path.read_text()
        begin='<!-- BEGIN PROJECT ENGINEERING -->'
        end='<!-- END PROJECT ENGINEERING -->'
        if text.count(begin)!=1 or text.count(end)!=1 or text.index(end)<text.index(begin):
            raise ValueError('Missing or malformed engineering instructions: '+name)
        section=text.split(begin,1)[1].split(end,1)[0]
        if '.engineering/POLICY.md' not in section or lock['sha256'] not in section:
            raise ValueError('Instruction provenance drift: '+name)
    print('PROJECT POLICY VERIFIED: pinned snapshot and all three agent references; product tests and release approval remain separate')


if __name__=='__main__':
    try: verify(Path(__file__).resolve().parents[1])
    except (OSError, ValueError, KeyError) as exc: raise SystemExit('PROJECT POLICY FAILED: '+str(exc))
