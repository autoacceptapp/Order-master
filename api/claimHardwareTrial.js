const { admin, db, authenticateRequest, TRIAL_DURATION_MS } = require("./_utils/firebase");

module.exports = async (req, res) => {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");

  if (req.method === "OPTIONS") {
    return res.status(200).end();
  }

  const caller = await authenticateRequest(req);
  if (!caller) {
    return res.status(401).json({ error: "Authentication required. User must be signed in with Google." });
  }

  const uid = caller.uid;
  const userEmail = caller.email || "";
  let body = req.body;
  if (typeof body === "string") {
    try { body = JSON.parse(body); } catch (_) { body = {}; }
  }
  body = body || {};
  const hardwareId = ((body.hardwareId) || "").trim();
  const deviceSpecs = body.deviceSpecs || {};

  if (!hardwareId || hardwareId.length < 8) {
    return res.status(400).json({ error: "Valid hardware ID required." });
  }

  const hardwareDocRef = db.collection("hardware_trials").doc(hardwareId);
  const userTrialDocRef = db.collection("user_trials").doc(uid);

  try {
    const result = await db.runTransaction(async (transaction) => {
      const hwSnap = await transaction.get(hardwareDocRef);
      const userTrialSnap = await transaction.get(userTrialDocRef);

      const now = Date.now();

      // Check if hardware already has a claimed trial
      if (hwSnap.exists) {
        const data = hwSnap.data();
        return {
          success: true,
          isNewClaim: false,
          firstClaimedTimestamp: data.firstClaimedTimestamp || now,
          trialExpiryTimestamp: data.trialExpiryTimestamp || (now + TRIAL_DURATION_MS),
          isUsed: true,
          message: "Existing trial active on this hardware.",
        };
      }

      // Check if user already claimed a trial on another device
      if (userTrialSnap.exists) {
        throw new Error("Your Google account has already claimed a free trial on another device.");
      }

      const expiryTimestamp = now + TRIAL_DURATION_MS;

      const trialRecord = {
        hardwareId,
        claimedByUid: uid,
        userEmail,
        firstClaimedTimestamp: now,
        trialExpiryTimestamp: expiryTimestamp,
        isUsed: true,
        deviceSpecs,
        createdAt: admin.firestore.FieldValue.serverTimestamp(),
        createdServerTimeMs: now,
      };

      transaction.set(hardwareDocRef, trialRecord);
      transaction.set(userTrialDocRef, {
        uid,
        hardwareId,
        claimedAt: admin.firestore.FieldValue.serverTimestamp(),
        claimedServerTimeMs: now,
      });

      return {
        success: true,
        isNewClaim: true,
        firstClaimedTimestamp: now,
        trialExpiryTimestamp: expiryTimestamp,
        isUsed: true,
        message: "2-Day Free Trial activated successfully.",
      };
    });

    return res.status(200).json(result);
  } catch (err) {
    console.error("claimHardwareTrial error:", err);
    return res.status(400).json({ error: err.message || "Failed claiming trial." });
  }
};
