/**
 * POST /api/purchasePassWithPoints
 * Verifies points balance and calculates expiry atomically on server.
 */

const { admin, db, PASS_TIERS, handleCors, authenticateUser } = require("./utils/firebase");

module.exports = async (req, res) => {
  if (handleCors(req, res)) return;

  if (req.method !== "POST") {
    return res.status(405).json({ error: "Method Not Allowed", message: "Only POST allowed" });
  }

  const user = await authenticateUser(req, res);
  if (!user) return;

  const uid = user.uid;
  const body = req.body || {};
  const passTierId = body.passTier || body.passTierId;
  const tier = PASS_TIERS[passTierId];

  if (!tier) {
    return res.status(400).json({ error: "Invalid Argument", message: `Unknown pass tier: ${passTierId}` });
  }

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

      if (currentPoints < tier.pointsCost) {
        const err = new Error(`Insufficient points. Balance: ${currentPoints}, Required: ${tier.pointsCost}`);
        err.statusCode = 412;
        throw err;
      }

      const serverNow = Date.now();
      const currentExpiry = userData.passExpiryTimestamp || 0;
      const baseTime = currentExpiry > serverNow ? currentExpiry : serverNow;
      const newExpiry = baseTime + tier.durationMs;
      const newPoints = currentPoints - tier.pointsCost;

      transaction.set(
        userDocRef,
        {
          pointsBalance: newPoints,
          activePassType: tier.id,
          passExpiryTimestamp: newExpiry,
          lastPurchasedTier: tier.id,
          lastPaymentMethod: "POINTS",
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
          updatedServerTimeMs: serverNow,
        },
        { merge: true }
      );

      return {
        success: true,
        activePassType: tier.id,
        passExpiryTimestamp: newExpiry,
        newPointsBalance: newPoints,
        serverTimeMs: serverNow,
      };
    });

    return res.status(200).json(result);
  } catch (error) {
    const status = error.statusCode || 500;
    console.error("Error in purchasePassWithPoints:", error.message);
    return res.status(status).json({ error: error.name || "Error", message: error.message });
  }
};
