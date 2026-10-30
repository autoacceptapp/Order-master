/**
 * POST /api/verifyUtrAndActivatePass
 * Validates bank UTR payment record server-side and extends pass expiry.
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
  const utr = (body.utr || "").trim();
  const passTierId = body.passTier || body.passTierId || "daily_pass";
  const tier = PASS_TIERS[passTierId] || PASS_TIERS.daily_pass;

  if (!/^\d{12}$/.test(utr)) {
    return res.status(400).json({ error: "Invalid Argument", message: "Invalid 12-digit numeric UTR." });
  }

  const paymentDocRef = db.collection("received_payments").doc(utr);
  const userDocRef = db.collection("users_licensing").doc(uid);

  try {
    const result = await db.runTransaction(async (transaction) => {
      const paymentSnap = await transaction.get(paymentDocRef);

      if (!paymentSnap.exists) {
        const pendingRef = db.collection("pending_verifications").doc(utr);
        transaction.set(
          pendingRef,
          {
            utr,
            userId: uid,
            passTierId: tier.id,
            requestedAt: admin.firestore.FieldValue.serverTimestamp(),
            status: "PENDING_MATCH",
          },
          { merge: true }
        );

        const err = new Error("Payment UTR not confirmed by bank yet. Pending admin verification.");
        err.statusCode = 404;
        throw err;
      }

      const paymentData = paymentSnap.data();
      if (paymentData.isClaimed || paymentData.claimedByUid || paymentData.isUsed) {
        const err = new Error("This UTR has already been claimed or used.");
        err.statusCode = 409;
        throw err;
      }

      if (paymentData.status && paymentData.status !== "Verified" && paymentData.status !== "VERIFIED") {
        const err = new Error("Payment has not been verified yet.");
        err.statusCode = 412;
        throw err;
      }

      const serverNow = Date.now();
      const userSnap = await transaction.get(userDocRef);
      const userData = userSnap.exists ? userSnap.data() : {};
      const currentExpiry = userData.passExpiryTimestamp || 0;
      const baseTime = currentExpiry > serverNow ? currentExpiry : serverNow;
      const newExpiry = baseTime + tier.durationMs;

      transaction.update(paymentDocRef, {
        isUsed: true,
        isClaimed: true,
        claimedBy: uid,
        claimedByUid: uid,
        claimedAt: admin.firestore.FieldValue.serverTimestamp(),
        passTierId: tier.id,
      });

      transaction.set(
        userDocRef,
        {
          userId: uid,
          activePassType: tier.id,
          passExpiryTimestamp: newExpiry,
          lastPurchasedTier: tier.id,
          lastPaymentMethod: "UPI_UTR",
          lastTransactionId: utr,
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
          updatedServerTimeMs: serverNow,
        },
        { merge: true }
      );

      return {
        success: true,
        activePassType: tier.id,
        passExpiryTimestamp: newExpiry,
        serverTimeMs: serverNow,
      };
    });

    return res.status(200).json(result);
  } catch (error) {
    const status = error.statusCode || 500;
    console.error("Error in verifyUtrAndActivatePass:", error.message);
    return res.status(status).json({ error: error.name || "Error", message: error.message });
  }
};
