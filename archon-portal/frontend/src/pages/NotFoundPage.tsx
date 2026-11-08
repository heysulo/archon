import { Button, Stack, Text, Title } from "@mantine/core";
import { Link } from "react-router";

export function NotFoundPage() {
  return (
    <Stack align="flex-start">
      <Title order={2}>Page not found</Title>
      <Text c="dimmed">There is nothing at this address.</Text>
      <Button component={Link} to="/processes" variant="light">
        Go to processes
      </Button>
    </Stack>
  );
}
