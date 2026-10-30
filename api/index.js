module.exports = async (req, res) => {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");

  if (req.method === "OPTIONS") {
    return res.status(200).end();
  }

  return res.status(200).json({
    status: "ok",
    service: "Order Master Serverless Backend",
    endpoints: [
      "/api/getServerTime",
      "/api/initializeUserLicensing",
      "/api/claimHardwareTrial",
      "/api/addPoints",
      "/api/purchasePassWithPoints",
      "/api/verifyUtrAndActivatePass",
      "/api/unlockFeatureWithPoints",
      "/api/claimReferralCode"
    ],
    timestamp: Date.now()
  });
};
