from pyngrok import ngrok, conf
from flask import Flask, request, jsonify
import torch
from transformers import AutoModelForCausalLM, AutoTokenizer
import threading

NGROK_TOKEN = "3CBe927vVBPbxS6VdisYkbYgqdq_6BNFu6vBtkz9isjiZ6TX"
MODEL_NAME = "SPACEAI1/space-ai-finance-full"

conf.get_default().auth_token = NGROK_TOKEN

print("Carico modello...")
tokenizer = AutoTokenizer.from_pretrained(MODEL_NAME)
model = AutoModelForCausalLM.from_pretrained(
    MODEL_NAME,
    device_map="auto",
    torch_dtype=torch.float16,
    trust_remote_code=True
)
model.eval()
print("Modello caricato!")

app = Flask(__name__)

@app.route("/v1/chat/completions", methods=["POST"])
def chat():
    data = request.json
    messages = data.get("messages", [])
    text = " ".join([m["content"] for m in messages])
    inputs = tokenizer(text, return_tensors="pt").to(model.device)
    with torch.no_grad():
        output = model.generate(
            **inputs,
            max_new_tokens=300,
            temperature=0.7,
            do_sample=True,
            pad_token_id=tokenizer.eos_token_id
        )
    response = tokenizer.decode(
        output[0][inputs.input_ids.shape[1]:],
        skip_special_tokens=True
    )
    return jsonify({"choices": [{"message": {"role": "assistant", "content": response}}]})

@app.route("/v1/models", methods=["GET"])
def models():
    return jsonify({"data": [{"id": "space-ai-finance"}]})

public_url = ngrok.connect(5000).public_url
print("URL pubblico:", public_url)
print("Usa in Render AI_BASE_URL:", public_url + "/v1")

threading.Thread(target=lambda: app.run(port=5000, use_reloader=False)).start()
