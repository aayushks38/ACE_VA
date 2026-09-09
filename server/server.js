const express = require('express');
const cors = require('cors');
const dotenv = require('dotenv');
const https = require('https');
const http = require('http');

dotenv.config();

const app = express();
const PORT = process.env.PORT || 3000;

app.use(cors());
app.use(express.json());

// 1. Health Check Endpoint
app.get('/health', (req, res) => {
    const rimeApiKey = process.env.RIME_API_KEY || '';
    const isRimeConfigured = rimeApiKey.trim().length > 0 && rimeApiKey !== 'your_rime_api_key_here' && rimeApiKey !== 'placeholder_rime_key';
    
    res.json({
        status: 'ok',
        rimeConfigured: isRimeConfigured,
        timestamp: new Date().toISOString()
    });
});

// 2. Authenticated Rime Proxy Voice Generation Endpoint
app.post('/voice', (req, res) => {
    const { text, speaker, modelId, speedAlpha } = req.body;

    // Validate Request Body
    if (!text || typeof text !== 'string' || text.trim().length === 0) {
        return res.status(400).json({ error: 'Bad Request: "text" field is required and must be a non-empty string.' });
    }

    // Optional Authorization Secret Validation
    const clientSecret = process.env.CLIENT_AUTH_SECRET;
    if (clientSecret && clientSecret.trim().length > 0) {
        const authHeader = req.headers.authorization || '';
        if (authHeader !== `Bearer ${clientSecret}`) {
            return res.status(401).json({ error: 'Unauthorized: Invalid client authorization token.' });
        }
    }

    // Check Server Rime Secret Configuration
    const rimeApiKey = process.env.RIME_API_KEY || '';
    const isConfigured = rimeApiKey.trim().length > 0 && rimeApiKey !== 'your_rime_api_key_here' && rimeApiKey !== 'placeholder_rime_key';

    if (!isConfigured) {
        return res.status(503).json({
            error: 'Rime API key is unconfigured on server. Set RIME_API_KEY in server/.env to enable live synthesis.',
            rimeConfigured: false
        });
    }

    // Prepare Request to Rime API
    const rimePayload = JSON.stringify({
        speaker: speaker || 'abbie',
        text: text.trim(),
        modelId: modelId || 'mist',
        samplingRate: 22050,
        speedAlpha: speedAlpha || 1.0,
        reduceLatency: true
    });

    const options = {
        hostname: 'users.rime.ai',
        port: 443,
        path: '/v1/rime_tts',
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${rimeApiKey}`,
            'Content-Length': Buffer.byteLength(rimePayload)
        }
    };

    const rimeReq = https.request(options, (rimeRes) => {
        if (rimeRes.statusCode >= 200 && rimeRes.statusCode < 300) {
            res.setHeader('Content-Type', rimeRes.headers['content-type'] || 'audio/wav');
            res.setHeader('X-Voice-Provider', 'Rime');
            rimeRes.pipe(res);
        } else {
            let errorData = '';
            rimeRes.on('data', chunk => { errorData += chunk; });
            rimeRes.on('end', () => {
                console.error(`Rime API Error [${rimeRes.statusCode}]:`, errorData);
                res.status(rimeRes.statusCode || 500).json({
                    error: `Rime API request failed with status ${rimeRes.statusCode}`,
                    provider: 'Rime'
                });
            });
        }
    });

    rimeReq.on('error', (err) => {
        console.error('Rime Network Connection Error:', err.message);
        res.status(502).json({
            error: `Failed to connect to Rime API server: ${err.message}`,
            provider: 'Rime'
        });
    });

    rimeReq.write(rimePayload);
    rimeReq.end();
});

app.listen(PORT, () => {
    console.log(`ACE Rime Voice Proxy Backend listening on port ${PORT}`);
});
