import 'dotenv/config';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { createSwonalMcpServer } from './mcpServer';

async function main() {
  const server = createSwonalMcpServer();
  const transport = new StdioServerTransport();
  await server.connect(transport);
  console.error('swonal-ui MCP server running on stdio');
}

main().catch((err) => {
  console.error('Failed to start MCP stdio server:', err);
  process.exit(1);
});
