/**
 * POST /api/claimReferralCode
 * Atomic transaction to claim referral rewards (+20 to referrer, +50 to referee).
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
  const submittedCode = (body.submittedCode || body.inviteCode || "").trim().toUpperCase();

  if (!submittedCode || submittedCode.length !== 8) {
    return res.status(400).json({ error: "Invalid Argument", message: "Referral code must be exactly 8 characters." });
  }

  try {
    const querySnap = await db.collection("users_licensing")
      .where("referralCode", "==", submittedCode)
      .limit(1)
      .get();

    if (querySnap.empty) {
      return res.status(404).json({ error: "Not Found", message: "Invalid referral code." });
    }

    const referrerDocSnap = querySnap.docs[0];
    const referrerUid = referrerDocSnap.id;

    if (referrerUid === uid) {
      return res.status(400).json({ error: "Invalid Argument", message: "Cannot use your own referral code." });
    }

    const userDocRef = db.collection("users_licensing").doc(uid);
    const referrerDocRef = db.collection("users_licensing").doc(referrerUid);

    const result = await db.runTransaction(async (transaction) => {
      const userSnap = await transaction.get(userDocRef);
      const freshReferrerSnap = await transaction.get(referrerDocRef);

      if (!freshReferrerSnap.exists) {
        const err = new Error("Referrer document not found.");
        err.statusCode = 404;
        throw err;
      }

      const userData = userSnap.exists ? userSnap.data() : {};
      if (userData.hasClaimedReferral) {
        const err = new Error("Bonus Already Claimed.");
        err.statusCode = 409;
        throw err;
      }

      const currentPoints = userData.pointsBalance || 0;
      const referrerPoints = freshReferrerSnap.data().pointsBalance || 0;
      const referrerCount = freshReferrerSnap.data().referralCount || 0;

      const newCallerPoints = currentPoints + 50;
      const newReferrerPoints = referrerPoints + 20;

      transaction.set(
        referrerDocRef,
        {
          pointsBalance: newReferrerPoints,
          referralCount: referrerCount + 1,
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        },
        { merge: true }
      );

      transaction.set(
        userDocRef,
        {
          userId: uid,
          pointsBalance: newCallerPoints,
          hasClaimedReferral: true,
          claimedFromCode: submittedCode,
          claimedAt: admin.firestore.FieldValue.serverTimestamp(),
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        },
        { merge: true }
      );

      return {
        success: true,
        newPointsBalance: newCallerPoints,
        message: "Referral claimed: +50 points added to your balance.",
      };
    });

    return res.status(200).json(result);
  } catch (error) {
    const status = error.statusCode || 500;
    console.error("Error in claimReferralCode:", error.message);
    return res.status(status).json({ error: error.name || "Error", message: error.message });
  }
};
