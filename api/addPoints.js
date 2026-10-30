/**
 * POST /api/addPoints
 * Secure server-side points credit (wallet top-ups or incentives).
 */

const { admin, db, handleCors, authenticateUser } = require("./utils/firebase");

module.exports = async (req, res) => {
  if (handleCors(req, res)) return;

  if (req.method !== "POST") {
    return res.status(405).json({ error: "Method Not Allowed", message: "Only POST allowed" });
  }

  const user = await authenticateUser(req, res);
  if (!user) return;

  const uid = user.uid;
  const body = req.body || {};
  const amount = parseInt(body.amount, 10);
  const reason = body.reason || "Wallet Top-up";

  if (isNaN(amount) || amount <= 0 || amount > 10000) {
    return res.status(400).json({ error: "Invalid Argument", message: "Amount must be a positive number up to 10,000." });
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
  } catch (error) {
    console.error("Error in addPoints:", error.message);
    return res.status(500).json({ error: "Internal Server Error", message: error.message });
  }
};
