const { admin, db, authenticateRequest } = require("./utils/firebase");

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
  const submittedCode = ((req.body && (req.body.submittedCode || req.body.inviteCode)) || "").trim().toUpperCase();

  if (!submittedCode || submittedCode.length !== 8) {
    return res.status(400).json({ error: "Referral code must be exactly 8 characters." });
  }

  try {
    const querySnap = await db.collection("users_licensing")
      .where("referralCode", "==", submittedCode)
      .limit(1)
      .get();

    if (querySnap.empty) {
      return res.status(404).json({ error: "Invalid referral code." });
    }

    const referrerDocSnap = querySnap.docs[0];
    const referrerUid = referrerDocSnap.id;

    if (referrerUid === uid) {
      return res.status(400).json({ error: "Cannot use your own referral code." });
    }

    const userDocRef = db.collection("users_licensing").doc(uid);
    const referrerDocRef = db.collection("users_licensing").doc(referrerUid);

    const result = await db.runTransaction(async (transaction) => {
      const userSnap = await transaction.get(userDocRef);
      const freshReferrerSnap = await transaction.get(referrerDocRef);

      if (!freshReferrerSnap.exists) {
        throw new Error("Referrer document not found.");
      }

      const userData = userSnap.exists ? userSnap.data() : {};
      if (userData.hasClaimedReferral) {
        throw new Error("Bonus Already Claimed.");
      }

      const currentPoints = userData.pointsBalance || 0;
      const referrerPoints = freshReferrerSnap.data().pointsBalance || 0;
      const referrerCount = freshReferrerSnap.data().referralCount || 0;

      const newCallerPoints = currentPoints + 50;
      const newReferrerPoints = referrerPoints + 20;

      // Update referrer
      transaction.set(
        referrerDocRef,
        {
          pointsBalance: newReferrerPoints,
          referralCount: referrerCount + 1,
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        },
        { merge: true }
      );

      // Update referee
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
  } catch (err) {
    console.error("claimReferralCode error:", err);
    return res.status(400).json({ error: err.message || "Failed claiming referral code." });
  }
};
