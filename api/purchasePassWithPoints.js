const { admin, db, authenticateRequest, PASS_TIERS } = require("./utils/firebase");

module.exports = async (req, res) => {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");

  if (req.method === "OPTIONS") {
    return res.status(200).end();
  }

  const caller = await authenticateRequest(req);
  if (!caller) {
    return res.status(401).json({ error: "Authentication required." });
  }

  const uid = caller.uid;
  const passTierId = (req.body && (req.body.passTier || req.body.passTierId));
  const tier = PASS_TIERS[passTierId];

  if (!tier) {
    return res.status(400).json({ error: `Unknown pass tier: ${passTierId}` });
  }

  const userDocRef = db.collection("users_licensing").doc(uid);

  try {
    const result = await db.runTransaction(async (transaction) => {
      const userSnap = await transaction.get(userDocRef);
      if (!userSnap.exists) {
        throw new Error("User licensing document not found.");
      }

      const userData = userSnap.data();
      const currentPoints = userData.pointsBalance || 0;

      if (currentPoints < tier.pointsCost) {
        throw new Error(`Insufficient points. Balance: ${currentPoints}, Required: ${tier.pointsCost}`);
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
  } catch (err) {
    console.error("purchasePassWithPoints error:", err);
    return res.status(400).json({ error: err.message || "Failed purchasing pass with points." });
  }
};
