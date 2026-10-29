---
paths:
  - "bin/homelab-deploy"
  - "deploy/**"
  - "config/repos.conf"
  - "config/deploy.conf"
  - "scripts/18-projects.sh"
---

# Testing homelab-deploy (F-018)

- Test without root with local bare repos and `DEPLOY_ROOT`/`HOMELAB_REPO`/`SYSTEMCTL`/`TOKEN_FILE` (see the
  docstring of `bin/homelab-deploy`); `DOCKER_HOST` for your own rootless Docker.
- You can't read the journal of system services: look in `/srv/homelab/state/`.
