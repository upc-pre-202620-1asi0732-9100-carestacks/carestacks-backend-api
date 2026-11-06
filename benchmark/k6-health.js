import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  vus: 10,            // 10 usuarios simultáneos
  duration: '30s',    // durante 30 segundos
  thresholds: {
    http_req_duration: ['p(95)<800'], // meta: 95% de respuestas en menos de 800 ms
    http_req_failed: ['rate<0.01'],   // meta: menos de 1% de errores
  },
};

const BASE_URL = 'https://carestacks-backend-api.onrender.com';

export default function () {
  const res = http.get(`${BASE_URL}/actuator/health`);
  check(res, {
    'status 200': (r) => r.status === 200,
    'status UP': (r) => r.body.includes('"status":"UP"'),
  });
  sleep(1);
}