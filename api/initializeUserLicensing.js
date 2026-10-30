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
    return res.status(401).json({ error: "Authentication required. Valid Firebase ID token missing or expired." });
  }

  const uid = caller.uid;
  const userEmail = caller.email || "";
  const userDocRef = db.collection("users_licensing").doc(uid);

  try {
    const result = await db.runTransaction(async (transaction) => {
      const userSnap = await transaction.get(userDocRef);

      if (userSnap.exists) {
        const data = userSnap.data();
        let referralCode = data.referralCode;
        if (!referralCode) {
          const allowedChars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
          let randomSuffix = "";
          for (let i = 0; i < 6; i++) {
            randomSuffix += allowedChars.charAt(Math.floor(Math.random() * allowedChars.length));
          }
          referralCode = `OM${randomSuffix}`;
          transaction.update(userDocRef, {
            referralCode,
            updatedAt: admin.firestore.FieldValue.serverTimestamp(),
          });
        }
        return {
          success: true,
          isNew: false,
          referralCode,
          pointsBalance: data.pointsBalance || 0,
          hasClaimedReferral: !!data.hasClaimedReferral,
          referralCount: data.referralCount || 0,
        };
      }

      const allowedChars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
      let randomSuffix = "";
      for (let i = 0; i < 6; i++) {
        randomSuffix += allowedChars.charAt(Math.floor(Math.random() * allowedChars.length));
      }
      const newCode = `OM${randomSuffix}`;

      const newRecord = {
        userId: uid,
        userEmail: userEmail,
        pointsBalance: 0,
        referralCode: newCode,
        hasClaimedReferral: false,
        referralCount: 0,
        activePassType: null,
        passExpiryTimestamp: 0,
        unlocked_features: {},
        createdAt: admin.firestore.FieldValue.serverTimestamp(),
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      };

      transaction.set(userDocRef, newRecord);

      return {
        success: true,
        isNew: true,
        referralCode: newCode,
        pointsBalance: 0,
        hasClaimedReferral: false,
        referralCount: 0,
      };
    });

    return res.status(200).json(result);
  } catch (err) {
    console.error("initializeUserLicensing error:", err);
    return res.status(500).json({ error: err.message || "Internal server error" });
  }
};
