export function nodeRuntimeCompatibility(currentVersion, expectedMajor) {
  const currentMajor = Number.parseInt(String(currentVersion).split(".")[0], 10);
  const requiredMajor = Number.parseInt(String(expectedMajor).trim(), 10);

  if (!Number.isInteger(currentMajor) || !Number.isInteger(requiredMajor)) {
    return {
      compatible: false,
      currentMajor,
      requiredMajor,
      reason: `invalid Node version metadata (current=${currentVersion}, required=${expectedMajor})`
    };
  }

  return {
    compatible: currentMajor === requiredMajor,
    currentMajor,
    requiredMajor,
    reason: currentMajor === requiredMajor
      ? null
      : `Node ${currentVersion} does not match required major ${requiredMajor}`
  };
}

export function parkIncompatibleRuntime(compatibility, options = {}) {
  const log = options.log ?? console.error;
  const setIntervalImpl = options.setIntervalImpl ?? setInterval;
  const park = options.park ?? (() => new Promise(() => {}));

  log(
    `[vocab-sync] ${compatibility.reason}; parking the process to prevent ` +
    "launchd restart and Dropbox log churn. Reinstall with the pinned Node runtime."
  );
  setIntervalImpl(() => {}, 24 * 60 * 60 * 1000);
  return park();
}
