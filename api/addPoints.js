const { admin, db, authenticateRequest } = require("./_utils/firebase");

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
  const amount = parseInt(body.amount, 10);
  const reason = body.reason || "Wallet Top-up";

  if (isNaN(amount) || amount <= 0 || amount > 10000) {
    return res.status(400).json({ error: "Amount must be a positive number up to 10,000." });
  }

  const userDocRef = db.collection("users_licensing").doc(uid);

  try {
    const result = await db.runTransaction(async (transaction) => {
      const userSnap = await transaction.get(userDocRef);
      const currentPoints = userSnap.exists ? (userSnap.data().pointsBalance || 0) : 0;
      const newPoints = currentPoints + amount;

      transaction.set(
        userDocRef,
        {
          userId: uid,
          pointsBalance: newPoints,
          lastPointsCredit: {
            amount,
            reason,
            creditedAt: admin.firestore.FieldValue.serverTimestamp(),
          },
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        },
        { merge: true }
      );

      return {
        success: true,
        addedPoints: amount,
        newPointsBalance: newPoints,
      };
    });

    return res.status(200).json(result);
  } catch (err) {
    console.error("addPoints error:", err);
    return res.status(500).json({ error: err.message || "Failed adding points." });
  }
};
