const fs = require('node:fs');
const path = require('node:path');

const EXTRA_SCOPES = ['release', 'deps', 'deploy'];
const PACKAGES_DIR = path.join(__dirname, 'packages');
// Projects that live outside packages/ (currently just the docs site).
const EXTRA_PROJECT_DIRS = [path.join(__dirname, 'docs')];

/**
 * Collects the allowed commit scopes: every package directory plus the explicit
 * project name of any project.json underneath it (the SDK bindings declare
 * names like `shulker-sdk-bindings-rust` that do not match their directory).
 *
 * This used to call `buildProjectGraphAndSourceMapsWithoutDaemon()`. That made
 * every single commit construct the full Nx project graph, which in turn shells
 * out to Gradle via @nx/gradle -- slow for everyone, and a hard failure on any
 * checkout whose path contains a space, since the plugin invokes gradlew.bat
 * unquoted. Walking the filesystem yields the same names without a build tool
 * in the commit path.
 */
const listProjectScopes = () => {
  const scopes = new Set();

  const walk = (dir, depth) => {
    if (depth > 3) return;

    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      if (!entry.isDirectory()) continue;
      if (
        ['node_modules', 'src', 'assets', 'proto', 'dist'].includes(entry.name)
      )
        continue;

      const child = path.join(dir, entry.name);
      if (dir === PACKAGES_DIR) scopes.add(entry.name);

      const projectFile = path.join(child, 'project.json');
      if (fs.existsSync(projectFile)) {
        const { name } = JSON.parse(fs.readFileSync(projectFile, 'utf8'));
        if (name) scopes.add(name);
      }

      walk(child, depth + 1);
    }
  };

  walk(PACKAGES_DIR, 0);

  for (const dir of EXTRA_PROJECT_DIRS) {
    const projectFile = path.join(dir, 'project.json');
    if (!fs.existsSync(projectFile)) continue;
    const { name } = JSON.parse(fs.readFileSync(projectFile, 'utf8'));
    scopes.add(name ?? path.basename(dir));
  }

  return [...scopes];
};

module.exports = {
  extends: ['@commitlint/config-conventional'],
  rules: {
    'scope-enum': () => [
      2,
      'always',
      [...listProjectScopes(), ...EXTRA_SCOPES],
    ],
  },
};
