/**
 * GET /api/getServerTime or POST /api/getServerTime
 * Authoritative server timestamp endpoint for anti-clock-tampering verification.
 */

const { handleCors } = require("./utils/firebase");

module.exports = async (req, res) => {
  if (handleCors(req, res)) return;

  const now = Date.now();
  return res.status(200).json({
    success: true,
    serverTimestampMs: now,
    isoTime: new Date(now).toISOString(),
  });
};
