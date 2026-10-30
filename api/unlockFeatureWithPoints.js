const { admin, db, authenticateRequest, FEATURE_PRICING } = require("./_utils/firebase");

module.exports = async (req, res) => {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");

  if (req.method === "OPTIONS") {
    return res.status(200).end();
  }

  const caller = await authenticateRequest(req);
  if (!caller) {
    return res.status(401).json({ error: "Authentication required." });
  }

  const uid = caller.uid;
  let body = req.body;
  if (typeof body === "string") {
    try { body = JSON.parse(body); } catch (_) { body = {}; }
  }
  body = body || {};
  const featureId = ((body.featureId) || "").trim();
  const hours = parseInt(body.hours, 10) || 1;

  if (isNaN(hours) || hours <= 0 || hours > 720) {
    return res.status(400).json({ error: "Hours must be a positive integer (max 720)." });
  }

  const pricing = FEATURE_PRICING[featureId];
  if (!pricing) {
    return res.status(400).json({ error: `Unknown feature ID: ${featureId}` });
  }

  const totalCost = pricing.pointsPerHour * hours;
  const userDocRef = db.collection("users_licensing").doc(uid);

  try {
    const result = await db.runTransaction(async (transaction) => {
      const userSnap = await transaction.get(userDocRef);
      if (!userSnap.exists) {
        throw new Error("User licensing document not found.");
      }

      const userData = userSnap.data();
      const currentPoints = userData.pointsBalance || 0;

      if (currentPoints < totalCost) {
        throw new Error(`Insufficient points. Balance: ${currentPoints}, Required: ${totalCost}`);
      }

      const serverNow = Date.now();
      const unlockedFeatures = userData.unlocked_features || {};
      const currentExpiry = unlockedFeatures[featureId] || 0;
      const baseTime = currentExpiry > serverNow ? currentExpiry : serverNow;
      const newExpiry = baseTime + (hours * 3600000);
      const newPoints = currentPoints - totalCost;

      transaction.set(
        userDocRef,
        {
          pointsBalance: newPoints,
          unlocked_features: {
            ...unlockedFeatures,
            [featureId]: newExpiry,
          },
          lastFeatureUnlock: {
            featureId,
            hours,
            pointsCost: totalCost,
            unlockedAt: admin.firestore.FieldValue.serverTimestamp(),
            newExpiry,
          },
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
          updatedServerTimeMs: serverNow,
        },
        { merge: true }
      );

      return {
        success: true,
        featureId,
        hours,
        pointsDeducted: totalCost,
        newPointsBalance: newPoints,
        expiryTimestamp: newExpiry,
        serverTimeMs: serverNow,
        message: `Unlocked ${pricing.title} for ${hours} hour(s) until ${new Date(newExpiry).toISOString()}`,
      };
    });

    return res.status(200).json(result);
  } catch (err) {
    console.error("unlockFeatureWithPoints error:", err);
    return res.status(400).json({ error: err.message || "Failed unlocking feature." });
  }
};
