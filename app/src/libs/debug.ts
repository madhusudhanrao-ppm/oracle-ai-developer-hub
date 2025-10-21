export const isDebug = (): boolean => {
  try {
    return typeof localStorage !== "undefined" && localStorage.getItem("debug") === "1";
  } catch {
    return false;
  }
};

export const debugLog = (...args: any[]) => {
  if (isDebug()) {
    // eslint-disable-next-line no-console
    console.debug(...args);
  }
};
