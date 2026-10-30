/**
 * POST /api/unlockFeatureWithPoints
 * Pay-Per-Use micro-transaction to temporarily unlock individual features by hour.
 */

const { admin, db, FEATURE_PRICING, handleCors, authenticateUser } = require("./utils/firebase");

module.exports = async (req, res) => {
  if (handleCors(req, res)) return;

  if (req.method !== "POST") {
    return res.status(405).json({ error: "Method Not Allowed", message: "Only POST allowed" });
  }

  const user = await authenticateUser(req, res);
  if (!user) return;

  const uid = user.uid;
  const body = req.body || {};
  const featureId = (body.featureId || "").trim();
  const hours = parseInt(body.hours, 10) || 1;

  if (isNaN(hours) || hours <= 0 || hours > 720) {
    return res.status(400).json({ error: "Invalid Argument", message: "Hours must be a positive integer (max 720)." });
  }

  const pricing = FEATURE_PRICING[featureId];
  if (!pricing) {
    return res.status(400).json({ error: "Invalid Argument", message: `Unknown feature ID: ${featureId}` });
  }

  const totalCost = pricing.pointsPerHour * hours;
  const userDocRef = db.collection("users_licensing").doc(uid);

  try {
    const result = await db.runTransaction(async (transaction) => {
      const userSnap = await transaction.get(userDocRef);
      if (!userSnap.exists) {
        const err = new Error("User licensing document not found.");
        err.statusCode = 404;
        throw err;
      }

      const userData = userSnap.data();
      const currentPoints = userData.pointsBalance || 0;

      if (currentPoints < totalCost) {
        const err = new Error(`Insufficient points. Balance: ${currentPoints}, Required: ${totalCost}`);
        err.statusCode = 412;
        throw err;
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
  } catch (error) {
    const status = error.statusCode || 500;
    console.error("Error in unlockFeatureWithPoints:", error.message);
    return res.status(status).json({ error: error.name || "Error", message: error.message });
  }
};
