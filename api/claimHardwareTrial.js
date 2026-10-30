/**
 * POST /api/claimHardwareTrial
 * Grants a hardware-locked 48-hour trial using authoritative server time.
 * Enforces one trial per physical device AND one trial per Google account.
 */

const { admin, db, TRIAL_DURATION_MS, handleCors, authenticateUser } = require("./utils/firebase");

module.exports = async (req, res) => {
  if (handleCors(req, res)) return;

  if (req.method !== "POST") {
    return res.status(405).json({ error: "Method Not Allowed", message: "Only POST allowed" });
  }

  const user = await authenticateUser(req, res);
  if (!user) return;

  const uid = user.uid;
  const userEmail = user.email || "";
  const body = req.body || {};
  const hardwareId = (body.hardwareId || "").trim();
  const deviceSpecs = body.deviceSpecs || {};

  if (!hardwareId || hardwareId.length < 8) {
    return res.status(400).json({ error: "Invalid Argument", message: "Valid hardware ID required." });
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
        const err = new Error("Your Google account has already claimed a free trial on another device.");
        err.statusCode = 409;
        throw err;
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
  } catch (error) {
    const status = error.statusCode || 500;
    console.error("Error in claimHardwareTrial:", error.message);
    return res.status(status).json({ error: error.name || "Error", message: error.message });
  }
};
