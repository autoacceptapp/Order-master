/**
 * POST /api/initializeUserLicensing
 * Creates or ensures user licensing record in users_licensing with unique 8-character referral code.
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
  const userEmail = user.email || "";
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
  } catch (error) {
    console.error("Error in initializeUserLicensing:", error);
    return res.status(500).json({ error: "Internal Server Error", message: error.message });
  }
};
